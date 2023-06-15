pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the Git Hub URL')
        string(name: 'ECR_Repo_Name',description: 'Enter the Image Name') 
        string(name: 'Jenkins_IP',description: 'Enter the Jenkins IP')
        string(name: 'Version_Number',description: 'Enter the Version Number')
        string(name: 'Region_Name',description: 'Enter the Region Name')
        string(name: 'Aws_Id' ,description: 'Enter the AWS Id')
        string(name: 'Workspace_name',defaultValue: 'ECS_Pipeline For Testing',description: 'Enter the Workspace name')
        string(name: 'AWS_Credentials_Id',description: 'Enter the AWS Credentials Id')
        string(name: 'Git_Credentials_Id',description: 'Enter the Git Credentials Id')
        string(name: 'ECR_Credentials',description: 'Enter the ECR Credentials')
        string(name: 'Stack_Name',description: 'Enter the Stack Name')
        string(name: 'S3_URL', defaultValue: 'https://yamlclusterecs.s3.amazonaws.com/master.yaml',description: 'Enter the S3 URL')
        string(name: 'SONAR_PROJECT_NAME',defaultValue: 'Demo' ,description: 'Enter the Sonar Project Name')
        string(name: 'MailToRecipients' ,description: 'Enter the Mail To Recipients')
        string(name: 'ALBEndpoint_URL' ,description: 'Enter the ALB Endpoint')

        choice  (choices: ["Baseline", "Full"],
                 description: 'Type of scan that is going to perform inside the container',
                 name: 'SCAN_TYPE')
        booleanParam (defaultValue: true,
                 description: 'Parameter to know if wanna generate report.',
                 name: 'GENERATE_REPORT')
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
               withDockerRegistry(credentialsId: "${ECR_Credentials}", url: 'https://${Aws_Id}.dkr.ecr.${Region_Name}.amazonaws.com') 
            {
                sh '''
                docker build -t ${ECR_Repo_Name} .     
                docker tag ${ECR_Repo_Name}:latest ${Aws_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                docker push ${Aws_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
               
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
                            aws cloudformation update-stack --stack-name ${Stack_Name} --template-url ${S3_URL} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${Aws_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} || true
                           '''
                        }
                    } else {
                        script {
                            sh '''
                            aws cloudformation create-stack --stack-name ${Stack_Name} --template-url ${S3_URL} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${Aws_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} 
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
                     target = "${ALBEndpoint_URL}"
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
