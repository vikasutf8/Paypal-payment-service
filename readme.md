### STEP 1:
- To set this up correctly, you need a Parent POM that holds all your modules together, and then each microservice gets its own Child POM.
- Paste this code into it. This is your Parent POM. Notice the <packaging>pom</packaging> — this tells Maven "I am not a runnable app, I am just a container for other modules."


### STEP 2: PORT SETUP

- globalConfig 8888
- user_service 8082


### STEP 3 : ymal global setup
- We will tell the Config Server to look for configuration files in a local folder called config-repo inside its resources.

  1. Create the config files:
     Inside globalConfig/src/main/resources/, create a folder named config-repo. Inside that folder, create these 4 files for user_service:
  2. Configure globalConfig to serve these files:
     Open globalConfig/src/main/resources/application.yml and add this:
     ```yaml
      server:
        port: 8888 # Default port for Spring Cloud Config Server
    
      spring:
        profiles:
          active: native # Tells Config Server to read from local filesystem, NOT Git
        cloud:
          config:
            server:
              native:
                search-locations: classpath:/config-repo # Where your yml files are
      ```

3. Verify the Server:
   Start your globalConfig application. Once it's running on 8888, open your browser and go to:
   👉 http://localhost:8888/user_service/dev
```shell
{
  "name": "user_service",
  "profiles": [
    "dev"
  ],
  "label": null,
  "version": null,
  "state": null,
  "propertySources": [
    {
      "name": "classpath:/user_service_config/user_service-dev.yml",
      "source": {
        "server.port": 8082,
        "spring.datasource.url": "jdbc:postgresql://localhost:5432/payflow_user_dev",
        "spring.datasource.username": "postgres",
        "spring.datasource.password": "password123",
        "spring.jpa.hibernate.ddl-auto": "update",
        "environment.name": "DEVELOPMENT"
      }
    },
    {
      "name": "classpath:/user_service_config/user_service.yml",
      "source": {
        "app.name": "PayFlow User Service",
        "app.description": "Base configuration for User Service"
      }
    }
  ]
}
```
4. Now we need to tell user_service to fetch its configuration from globalConfig when it starts up.
    - Create application.yml in user_service:
   Open user_service/src/main/resources/application.yml and add this:
5. tested :http://localhost:8082/config
```shell
{
  "environment": "DEVELOPMENT",
  "appName": "PayFlow User Service"
}
```

### STEP 4 :
- The Workflow (How to refresh without restarting)
Now, here is what your actual workflow looks like when you want to change a configuration while the system is running:

Start globalConfig (Port 8888).
Start user_service (Port 8081). It reads environment.name: DEVELOPMENT from the server.
Go to http://localhost:8081/config -> You see Environment: DEVELOPMENT.
Make a change: Go to your IDE, open globalConfig/src/main/resources/config-repo/user_service-dev.yml, and change the value:
yaml

environment:
name: DEVELOPMENT_UPDATED
(Note: Because globalConfig uses the native profile, it reads from the classpath. You might need to restart globalConfig for it to see the file change if you are editing the file directly in the IDE. In production, this is a Git repo, so the Config Server detects Git pushes automatically).
Trigger the Refresh: Open a terminal (or Postman) and send an empty POST request to the CLIENT (user_service):
bash

curl -X POST http://localhost:8081/actuator/refresh
See the magic: Go back to http://localhost:8081/config -> You will now see Environment: DEVELOPMENT_UPDATED.
user_service did not restart, but its configuration updated!

Summary for your current level:
Server (globalConfig): Just holds the files. (Later, when connected to Git, it auto-updates when you push to Git).
Client (user_service): Holds the /actuator/refresh endpoint. You call this endpoint to tell the client "Go ask the Server for new values."
Later (Kafka/Bus): You will call /actuator/busrefresh on any service, and Kafka will broadcast a message to all services to refresh themselves. No more manual curl commands!