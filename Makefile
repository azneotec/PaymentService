# Runs PaymentService on its own: MySQL and Kafka from docker-compose.yml plus the app, no other service.
# DB_*, SERVER_PORT, EUREKA_CLIENT_ENABLED and the Stripe/Razorpay settings come from the gitignored .env
# (see .env.example; `make env` creates it). Without it the defaults in application.properties apply (the compose
# MySQL on 3307, Kafka on 9092). Spring resolves them at runtime, so the jar contains only ${...} placeholders,
# never the values. The root Makefile of the monorepo runs the whole stack instead (`make payment`).
#
#   make up        start MySQL and Kafka, then run the app in the foreground on :8005
#   make down      stop MySQL and Kafka (keeps their data)

SHELL      := /bin/bash
.DEFAULT_GOAL := help

JAR        := target/PaymentService-0.0.1-SNAPSHOT.jar
SKIP_TESTS ?= true

# Export .env for one recipe line when present. Sourced by the shell (not `include`) so quoted values work.
WITH_ENV = set -a; [ ! -f ./.env ] || . ./.env; set +a;

.PHONY: help env up down jar run test test-integration clean infra-up infra-down infra-reset

help: ## Show this help
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-17s %s\n", $$1, $$2}'

env: ## Create .env from .env.example if it doesn't exist
	@test -f .env || (cp .env.example .env && echo "Created .env - fill in your Stripe key before running.")

up: env infra-up run ## Start MySQL and Kafka, then build and run the app in the foreground

down: infra-down ## Stop MySQL and Kafka, keeping their data

jar: ## Build the jar (tests skipped by default; use SKIP_TESTS=false to run them)
	@$(WITH_ENV) ./mvnw clean package -DskipTests=$(SKIP_TESTS)
	@echo "Built $(JAR)"

run: env ## Run the jar with .env loaded (builds it first if missing; SERVER_PORT=9005 make run starts a second instance)
	@test -f $(JAR) || $(MAKE) --no-print-directory jar
	@$(WITH_ENV) java -jar $(JAR)

test: ## Run the unit tests (no database needed)
	@./mvnw test

test-integration: ## Run the integration tests against MySQL on localhost:3307 (make infra-up first)
	@STRIPE_API_KEY=$${STRIPE_API_KEY:-dummy} ./mvnw test -DexcludedGroups=none -Dgroups=integration

clean: ## Delete target/
	@./mvnw clean

infra-up: ## Start MySQL (host port 3307) and Kafka (9092, UI on 8090) from docker-compose.yml and wait until healthy
	docker compose up -d --wait

infra-down: ## Stop MySQL and Kafka, keeping their data
	docker compose down

infra-reset: ## Stop MySQL and Kafka and delete their data volumes
	docker compose down -v
