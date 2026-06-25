STEP 1:
- To set this up correctly, you need a Parent POM that holds all your modules together, and then each microservice gets its own Child POM.
- Paste this code into it. This is your Parent POM. Notice the <packaging>pom</packaging> — this tells Maven "I am not a runnable app, I am just a container for other modules."


STEP 2: PORT SETUP

- globalConfig 8888
- user_service 8082


STEP 3 : ymal global setup
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
