pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id' ,description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients' ,description: 'Enter the Mail Id for Approval')
        string(name: 'Endpoint_URL' ,description: 'Enter the Endpoint URL for OWASP Analysis')
        choice  (choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
                 description: 'Select your Region Name (eg: us-east-1). To Know your region code refer URL "https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RegionsAndAvailabilityZones.html#Concepts.RegionsAndAvailabilityZones.Regions" ',
                 name: 'Region_Name')  
        string(name: 'ECR_Repo_Name', defaultValue: 'test',description: 'ECR Repositary (Default: test)') 
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name',defaultValue: 'ECS_Pipeline_For_Testing',description: 'Workspace name')      
        string(name: 'AWS_Credentials_Id',defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id',defaultValue: 'Github_Credentials',description: 'Git Credentials Id')
        string(name: 'Stack_Name', defaultValue: 'ECS' ,description: 'Stack Name (Default: ECS)')
        string(name: 'SONAR_PROJECT_NAME',defaultValue: 'Demo' ,description: 'Sonar Project Name (Default: Demo)')
        choice  (choices: ["Baseline", "Full"],
                 description: 'Type of scan for OWASP Analysis',
                 name: 'SCAN_TYPE')
    }
    environment {
        ECR_Credentials = "ecr:${Region_Name}:AWS_Credentials"
        S3_Url          = 'https://yamlclusterecs.s3.amazonaws.com/master.yaml'
    }
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', credentialsId: "${Git_Credentials_Id}", url: "${Git_Hub_URL}"
                }
        }
        stage('Docker start') {
            steps {
                sh '''
                sudo chmod 666 /var/run/docker.sock
                docker start sonarqube
                docker start owasp
                curl ipinfo.io/ip > ip.txt
                '''
            }
        }
        stage('Wait for SonarQube to Start') {
            steps {
                script {
                    sleep 120 
                }
            }
        }
        stage('SonarQube Analysis') {
            steps {
            script {
                def scannerHome = tool 'sonarqube'; 
                withSonarQubeEnv('Default')  {
                sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${SONAR_PROJECT_NAME}"
                    }
                }
            }
        }
        stage('Send Sonar Analysis Report and Approval Email for Build Image') {
            steps {
                script {
                    def Jenkins_IP = sh(
                        returnStdout: true,
                        script: 'cat ip.txt'
                    )
                emailext (
                    subject: "Approval Needed to Build Docker Image",
                    body: "SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${SONAR_PROJECT_NAME} \n Username: admin /n Password: 12345 \n Please Approve to Build the Docker Image in Testing Environment\n\n${BUILD_URL}input/",
                    mimeType: 'text/html',
                    recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                    from: "nithincloudnativegmail.com",
                    to: "${MailToRecipients}",              
                )
            }
        }
        }
        stage('Approval-Build Image') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Please approve the build image process by clicking the link provided in the email.', ok: 'Proceed'
                }
            }
        }
        stage('Create a ECR Repository') {
            steps {
                withCredentials([[
                $class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: "${AWS_Credentials_Id}",
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                sh '''
                aws ecr create-repository --repository-name ${ECR_Repo_Name} --region ${Region_Name} || true
                cd /var/lib/jenkins/workspace/${Workspace_name}
                '''
                }
            }
        }
        stage('Build and Push the Docker Image to ECR Repository') {
            steps {
               withDockerRegistry(credentialsId: "${ECR_Credentials}", url: 'https://${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com') 
            {
                sh '''
                docker build -t ${ECR_Repo_Name} .     
                docker tag ${ECR_Repo_Name}:latest ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                docker push ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
               
                '''
             }
            }
        }
        stage('Deploy the Stack') {
            steps {
                withCredentials([[
                $class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: "${AWS_Credentials_Id}",
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                script {
                    def stackExists = sh(
                        returnStatus: true,
                        script: 'aws cloudformation describe-stacks --stack-name ${Stack_Name}'
                    )
                    if (stackExists == 0) {
                        script {
                            sh '''
                            aws cloudformation update-stack --stack-name ${Stack_Name} --template-url ${S3_Url} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} || true
                           '''
                        }
                    } else {
                        script {
                            sh '''
                            aws cloudformation create-stack --stack-name ${Stack_Name} --template-url ${S3_Url} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} 
                            '''
                        }
                    }

                }
            }
        } }
        stage('Approval for stack creation') {
            steps {
                timeout(time: 900, unit: 'MINUTES') {
                    input message: 'Please approve the deploy process by clicking the link provided in the email.', ok: 'Proceed'
                }
            }
        }
         stage('Scanning target on owasp container') {
             steps {
                 script {
                     scan_type = "${params.SCAN_TYPE}"
                     echo "----> scan_type: $scan_type"
                     target = "${Endpoint_URL}"
                     if(scan_type == "Baseline"){
                         sh """
                             docker exec owasp zap-baseline.py -t $target -r report.html -I 
                         """
                     }
                    else if(scan_type == "Full"){
                         sh """
                             docker exec owasp zap-full-scan.py -t $target -r report.html -I
                         """
                          }
                     else{
                         echo "Something went wrong..."
                     }
                 }
             }
         }
         stage('Copy Report to Workspace'){
             steps {
                 script {
                     sh '''
                            docker cp owasp:/zap/wrk/report.html ${WORKSPACE}/report.html
                     '''
                 }
             }
         }
         stage('Send Approval Email for Production') {
            steps {
                emailext (
                    subject: "Approval Needed to Production Environment",
                    body: '${FILE,path="report.html"} \n Please Verify the Testing Environment and give approval to Production Environment\n\n${BUILD_URL}input/',
                    mimeType: 'text/html',
                    recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                    from: "nithincloudnative@gmail.com",
                    to: "${MailToRecipients}",
                    attachLog: true
                )
            }
        }
}
}
