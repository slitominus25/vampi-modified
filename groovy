pipeline {
  options {
      buildDiscarder(logRotator(artifactNumToKeepStr: '1', numToKeepStr : '1')) //Сносим старые отчёты для красоты
      skipDefaultCheckout()  // делаем Checkout кода только один раз
  }
  environment {   //Объявление глобальных переменных
    ptcsAddr = "https://192.168.62.21"
    ptcsUser = "jenkins_cicd"
    ptcsPassword = "P@ssw0rd"
	ptaiAddr = "https://ptai-server.ptdemo.local"
	ptbbAddr = "https://ptbb-server.ptdemo.local"
    IMAGE_NAME = "vampi"
    report_format = "html"
    configRuleID = "0a53e90d-e5a5-4a6f-be33-857a718f844e"
    configDir = "./"
    vampiAddr = "https://vampi.ptdemo.local"
	vampiToken = ''
	vampiCheckUrl = ''
  }
  agent { 
    label 'master'
  }
  stages {  //Забираем код из репы
    stage('scm') {
        steps {
            git branch: 'master', url: 'https://gogs.ptdemo.local/applications/vampi.git'
            stash includes: '**/*', name: 'app'
        }
    }
    stage('sast') {  //Этап САСТ анализа исходников AppInspector
      agent { 
        label 'sast'
      }
      steps {
	    withCredentials([string(credentialsId: 'ptai-token', variable: 'TOKEN')]) {
          unstash 'app' 
          sh """
            mkdir -p .ptai
            rm -f ./.ptai/*
		    /opt/aisa/aisa --set-settings -u ${ptaiAddr} -t ${TOKEN}
			/opt/aisa/aisa \
			--project-name workshop-vampi \
			--scan-target . \
			--report HTML \
			--reports-folder ./.ptai
          """
        }
      }
      post {  //Сейвим артефакты
        always {
          archiveArtifacts artifacts: '.ptai/*'
        }
      }
    }

    stage('Get latest ptcs-cli') {  //Получаем свежую версию ptcs-cli
      steps{
        sh "curl -LO -k $ptcsAddr/file/ptcs-cli"
        sh "chmod +x ./ptcs-cli"
        
      }
    }
    stage('Scan VamPI Dockerfile in PTCS'){ //Проверка докерфайла приложения через ptcs 
      environment {
          //FIND_PATH = "*/${configDir}/*"
          FIND_PATH = "*"
      }
      steps {      
          sh """
            find . -type f -name Dockerfile -path "${FIND_PATH}" -exec sh -c \'./ptcs-cli scan dockerfile --login=${ptcsUser} --password=${ptcsPassword} \
            --enforced-rules=${configRuleID} \
            --ptcsurl=${ptcsAddr} --log-level=DEBUG --tls-skip-verify --report-output=report_dockerfile.${report_format} \
            --report-format=${report_format} \
            \$1\' -- {} ";"
            """
        }
    }
    stage('Build image'){  //Билдим image
        steps {
          withCredentials([file(credentialsId: 'harbor', variable: 'harbor')]) {
                sh '''
                  mkdir -p ~/.docker
                  cp \"${harbor}\" ~/.docker/config.json
                  docker login harbor.ptdemo.local
                  docker build --tag ${IMAGE_NAME} .
                  docker tag ${IMAGE_NAME} harbor.ptdemo.local/ptdemo/${IMAGE_NAME}
                  docker push harbor.ptdemo.local/ptdemo/${IMAGE_NAME}
                '''
          }
        }
    }
    stage('Scan VamPI image in PTCS'){ //Проверка имеджа приложения через ptcs 
      steps {
          sh "./ptcs-cli scan image --log-level='DEBUG' --login=${ptcsUser} --password=${ptcsPassword} --ptcsurl=${ptcsAddr} --report-format=${report_format} --report-output=report_image.${report_format} ${IMAGE_NAME}"
        }
    }
    stage('Scan VamPI YAML in PTCS'){ //Сканирования yaml через ptcs
      environment {
          //FIND_PATH = "*/${configDir}/*"
          FIND_PATH = "*"
      }
      steps {
          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            sh """
                echo "=== Scanning config folders==="
                echo "" > output.yaml
                find . -type f -name '*.yaml' -path "${FIND_PATH}" -print -exec sh -c 'cat \$1 >> output.yaml && echo "\n---\n" >> output.yaml ' -- {} ";"
                echo "=== Scanning config with PT CS: ===" 
                cat output.yaml
                ./ptcs-cli scan kubernetes --login=${ptcsUser} --password=${ptcsPassword} \
                --enforced-rules=${configRuleID} \
                --ptcsurl=${ptcsAddr} --log-level=DEBUG --tls-skip-verify --report-output=report_kubernetes.${report_format} \
                --report-format=${report_format} \
                output.yaml"""
          }
        }
    }
    stage('Deploy VamPI') {  //Деплоим приложение
        steps{
            withKubeConfig([credentialsId: 'dbf9e2d4-cbc1-41bc-b23a-77c81d93e1a3', serverUrl: 'https://192.168.62.20:6443']) {
                sh 'curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.20.5/bin/linux/amd64/kubectl"'  
                sh 'chmod u+x ./kubectl'
                sh "./kubectl apply -f k8s/k8s.yaml -n default"
            }
        }
    }
    stage('wait vampi is Up') { //Ожидаем пока оно подниметься
        steps {
            timeout(5) {
                waitUntil {
                  script {
                    try {
                        def response = httpRequest "${vampiAddr}"
                        return (response.status == 200)
                    }
                    catch (exception) {
                        return false
                    }
                  }
                }
            }
        }
    }
    stage('vampi auth and get token') { //Получаем авторизационный токен
        steps {
          script {
            // populate db
            sh "curl --location '${vampiAddr}/createdb'"
            // auth and get JSON with token
            def vampiAuthJson = sh (
                script: 'curl --insecure --location "${vampiAddr}/users/v1/login" --header "Content-Type: application/json" --header "Accept: application/json" --data \'{"username": "admin", "password": "pass1" }\' ',
                returnStdout: true
                ).trim()
            echo "vampi auth json: ${vampiAuthJson}"
            def jsonObj = readJSON text: vampiAuthJson
            vampiToken = jsonObj.auth_token
            echo "json token: ${vampiToken}"
            // get list of books and parse first book title
            def getBookTitle = sh (
                script: 'curl --insecure --location "${vampiAddr}/books/v1" --header "Content-Type: application/json" --header "Accept: application/json" ',
                returnStdout: true
                ).trim()
            jsonObj = readJSON text: getBookTitle
            def bookTitle = jsonObj.Books[0].book_title
            echo "book title: ${bookTitle}"
            //check auth with token
            vampiCheckUrl = "${vampiAddr}/books/v1/${bookTitle}"     
            def checkAuth = sh (
                script: "curl --insecure -s -w '%{http_code}\n' --location '${vampiCheckUrl}' --header 'Content-Type: application/json' --header 'Accept: application/json' --header 'Authorization: Bearer ${vampiToken}' -o /dev/null ",
                returnStdout: true,
                ).trim()
                //"status": "fail",
            echo "auth result: ${checkAuth}"
            if (checkAuth != "200") {
                currentBuild.result = "FAILURE"
                throw new Exception("VAMPI FAIL Auth Check")
            }
            
          }
        }
    }
    stage('dast') { //Сканируем поднятое приложение BlackBox
      agent { 
        label 'dast'
      }
      steps {
        script {
          writeFile file: 'dastAuthRule.txt', text: "TYPE=bearer\nTOKEN=${vampiToken}\nSUCCESS_URL=${vampiCheckUrl}\nSUCCESS_STRING=secret for"
          sh 'cat dastAuthRule.txt'
          sh 'ls -la'
        }
	    withCredentials([string(credentialsId: 'ptbb-token', variable: 'TOKEN')]) {
          sh '''
            mkdir -p ./.ptbb
            rm -f ./.ptbb/*
		    /opt/bb/venv/bin/python /opt/bb/BlackBox-CI-CD-script-master/main.py \
		    --blackbox-url ${ptbbAddr} \
		    --blackbox-api-token ${TOKEN} \
		    --target-uuid aeaf2d6d-d763-4efb-aa3e-52ab8ba52ac7 \
		    --ignore-ssl \
		    --report-dir .ptbb \
		    --report-template html \
		    --report-locale ru \
		    --auth-data dastAuthRule.txt \
		    --fail-under-score 1
          '''
        }
      }
      post {
        always {
          archiveArtifacts artifacts: '.ptbb/*.html'
        }
      }
    }
	
  }
    post { //Сохраняем отчёт
      always {
        archiveArtifacts artifacts: 'report*'
      }
    }
}