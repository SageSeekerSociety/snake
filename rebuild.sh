#!/bin/bash

./mvnw clean package
if [ $? -ne 0 ]; then
  echo "Build failed. Please check the output for errors."
  exit 1
fi

cp backend-controller/target/snake.controller-0.0.1-SNAPSHOT.jar backend-controller/target/snake-controller.jar
cp backend-worker/target/snake.worker-0.0.1-SNAPSHOT.jar backend-worker/target/snake-worker.jar

echo "Build completed successfully. JAR files are ready in the target directories."

cd docker-compose-deploy
sudo docker compose -f docker-compose.local.yml build

if [ $? -ne 0 ]; then
  echo "Docker build failed. Please check the output for errors."
  exit 1
fi

sudo docker compose -f docker-compose.local.yml up -d
