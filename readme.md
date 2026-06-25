STEP 1:
- To set this up correctly, you need a Parent POM that holds all your modules together, and then each microservice gets its own Child POM.
- Paste this code into it. This is your Parent POM. Notice the <packaging>pom</packaging> — this tells Maven "I am not a runnable app, I am just a container for other modules."


STEP 2: PORT SETUP

- globalConfig 8888
- user_service 8082