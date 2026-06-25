# Local Setup Guide

This guide walks you through setting up Kingdom Tactics for local development.

---

## Prerequisites

Install these before starting:

- **Java 21+** — [download](https://www.oracle.com/java/technologies/downloads/)
  ```bash
  java -version
  # openjdk version "21.0.1"
  ```

- **Node.js 18+** — [download](https://nodejs.org/)
  ```bash
  node -v
  # v18.18.2
  npm -v
  # 9.8.1
  ```

- **Docker & Docker Compose** — [download](https://www.docker.com/products/docker-desktop)
  ```bash
  docker -v
  # Docker version 24.0.6
  docker-compose -v
  # Docker Compose version v2.21.0
  ```

- **Git** — [download](https://git-scm.com/)
  ```bash
  git --version
  # git version 2.42.0
  ```

---

## Step 1: Clone the repository

```bash
git clone https://github.com/your-username/kingdom-tactics.git
cd kingdom-tactics
```

---

## Step 2: Set up environment variables

Copy the example file:

```bash
cp .env.example .env
```

Edit `.env` if needed (defaults should work for local development):

```env
# Backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/kingdom_tactics
SPRING_DATASOURCE_USERNAME=kingdom_user
SPRING_DATASOURCE_PASSWORD=kingdom_password
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379

# Frontend
VITE_API_BASE_URL=http://localhost:8080/api

# Docker Compose
POSTGRES_DB=kingdom_tactics
POSTGRES_USER=kingdom_user
POSTGRES_PASSWORD=kingdom_password
```

---

## Step 3: Start services with Docker Compose

From the project root, run:

```bash
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- Redis on port 6379

Verify they're running:

```bash
docker-compose ps
# Should show postgres and redis as "Up"
```

---

## Step 4: Set up the backend

```bash
cd backend

# Build the project
./mvnw clean install

# Run migrations (Flyway)
# This happens automatically on application startup

# Start the API
./mvnw spring-boot:run
```

The API should be available at `http://localhost:8080`.

Check the health endpoint:

```bash
curl http://localhost:8080/health
# { "status": "UP", "database": { "status": "UP" }, ... }
```

### Backend troubleshooting

**Port 8080 already in use:**
```bash
# Find and kill the process
lsof -ti:8080 | xargs kill -9

# Or run on a different port
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

**Database connection error:**
```bash
# Verify postgres is running
docker-compose logs postgres

# Rebuild containers if needed
docker-compose down
docker-compose up -d
```

**Migration errors:**
```bash
# Check Flyway logs
docker-compose logs postgres | grep flyway

# Reset database (CAUTION: deletes all data)
docker exec kingdom-tactics-postgres psql -U kingdom_user -d postgres -c "DROP DATABASE IF EXISTS kingdom_tactics;"
```

---

## Step 5: Set up the frontend

In a new terminal (keep backend running):

```bash
cd frontend

# Install dependencies
npm install

# Start the dev server
npm run dev
```

The frontend should be available at `http://localhost:5173`.

You should see the landing page.

### Frontend troubleshooting

**Port 5173 already in use:**
```bash
npm run dev -- --port 3000
```

**Module not found errors:**
```bash
# Clear node_modules and reinstall
rm -rf node_modules package-lock.json
npm install
```

**API connection errors:**
Check that the backend is running and the `VITE_API_BASE_URL` in `.env` is correct.

---

## Step 6: Verify the full stack

### Check all services

```bash
# Terminal 1: Backend running on 8080
curl http://localhost:8080/health

# Terminal 2: Frontend running on 5173
curl http://localhost:5173

# Terminal 3: Docker services
docker-compose ps
```

### Test the API directly

```bash
# Register a user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"player1","email":"player1@example.com","password":"password123"}'

# Response should include a token
# { "userId": "...", "token": "..." }

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"player1@example.com","password":"password123"}'

# Get current user
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <token_here>"
```

---

## Running tests

### Backend tests

```bash
cd backend

# Unit tests only
./mvnw test

# Unit + integration tests (requires Docker)
./mvnw verify

# Run a specific test class
./mvnw test -Dtest=CombatEngineTest

# Run a specific test method
./mvnw test -Dtest=CombatEngineTest#ranger_targets_lowest_hp_unit
```

### Frontend tests

```bash
cd frontend

# Unit tests
npm run test

# Watch mode (re-run on file changes)
npm run test -- --watch

# E2E tests (requires backend and frontend running)
npm run test:e2e
```

### All tests with CI environment

```bash
# Simulates GitHub Actions locally
docker-compose up -d
cd backend && ./mvnw verify
cd ../frontend && npm run test && npm run test:e2e
docker-compose down
```

---

## Database management

### View the database

```bash
# Connect to PostgreSQL
docker exec -it kingdom-tactics-postgres psql -U kingdom_user -d kingdom_tactics

# List tables
\dt

# Query users
SELECT id, username, email FROM users;

# Exit
\q
```

### Reset the database

```bash
# WARNING: This deletes all data
docker exec kingdom-tactics-postgres dropdb -U kingdom_user kingdom_tactics

# Recreate and run migrations
docker-compose restart postgres
```

### Inspect Redis

```bash
# Connect to Redis
docker exec -it kingdom-tactics-redis redis-cli

# View all keys
KEYS *

# Exit
quit
```

---

## Development tips

### Hot reload

**Backend:**
- Install Spring Boot DevTools (already in pom.xml)
- Changes to Java files auto-reload on save
- Restart may be needed for bean changes

**Frontend:**
- Vite auto-reloads on file save
- Changes appear instantly in browser

### Debugging

**Backend:**
```bash
# Run with debugger on port 5005
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"

# Then attach your IDE debugger to localhost:5005
```

**Frontend:**
- Open Chrome DevTools (F12)
- React DevTools extension recommended
- Network tab shows API calls

### Database schema

View the latest schema:

```bash
# Backend generates schema.sql from migrations
cat backend/src/main/resources/db/migration/*.sql

# Or query the database
docker exec kingdom-tactics-postgres pg_dump -U kingdom_user kingdom_tactics --schema-only
```

---

## Common tasks

### Create a new game programmatically

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test_user","email":"test@example.com","password":"pass123"}' \
  | jq -r '.token')

curl -X POST http://localhost:8080/api/games \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

### Clear all logs

```bash
# Restart services (clears Docker logs)
docker-compose restart
```

### Update dependencies

**Backend:**
```bash
cd backend
./mvnw versions:display-dependency-updates
./mvnw dependency:update-help
```

**Frontend:**
```bash
cd frontend
npm outdated
npm update
```

---

## Deployment preview

To test the production Docker image locally:

```bash
# Build Docker image
docker build -t kingdom-tactics:latest .

# Run it
docker run -p 8080:8080 kingdom-tactics:latest
```

---

## Stopping services

```bash
# Stop all containers
docker-compose stop

# Stop and remove all containers
docker-compose down

# Stop containers and remove volumes (CAUTION: deletes data)
docker-compose down -v
```

---

## Help and troubleshooting

### Check logs

```bash
# Backend logs
tail -f backend.log

# Docker service logs
docker-compose logs -f postgres
docker-compose logs -f redis

# Frontend errors (check browser console)
# Press F12 in Chrome → Console tab
```

### Performance issues

```bash
# Check CPU and memory usage
docker stats

# If slow, allocate more resources to Docker
# Docker Desktop → Preferences → Resources → Increase CPU/Memory
```

### Network issues

If API calls fail with CORS errors:
1. Verify frontend is on `localhost:5173`
2. Verify backend is on `localhost:8080`
3. Check `VITE_API_BASE_URL` matches backend URL
4. Backend CORS config should whitelist `http://localhost:5173`

---

## Next steps

1. Read [docs/rules.md](../docs/rules.md) to understand the game
2. Review [ROADMAP.md](../ROADMAP.md) to see what to build next
3. Start working on [Phase 1 — Combat Engine](../ROADMAP.md#phase-1-pure-deterministic-combat-engine)

---

## Questions?

- Check the [README](../README.md)
- Review the [FAQ](../README.md#faq)
- Open an issue on GitHub
