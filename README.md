# ecommerce-catalog-service
I will develop an e-commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t catalog-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag catalog-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-catalog-service:latest
docker push bmcnpnr/ecommerce-catalog-service:latest
