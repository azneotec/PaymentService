.DEFAULT_GOAL := help

MVN := ./mvnw
JAR := target/PaymentService-0.0.1-SNAPSHOT.jar

.PHONY: help env run build start test clean

help: ## Show available commands
	@grep -E '^[a-z]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*?## ' '{printf "  make %-8s %s\n", $$1, $$2}'

env: ## Create .env from .env.example if it doesn't exist
	@test -f .env || (cp .env.example .env && echo "Created .env - fill in your Razorpay keys before running.")

run: env ## Start the service with Maven (dev mode, port 8080)
	$(MVN) spring-boot:run

build: ## Package the service into a jar
	$(MVN) clean package -DskipTests

start: env build ## Build the jar and run it
	java -jar $(JAR)

test: ## Run the tests
	$(MVN) test

clean: ## Remove build output
	$(MVN) clean
