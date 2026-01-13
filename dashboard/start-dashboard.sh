#!/bin/bash
# Dashboard start script with automatic port selection

# Read port from output.properties
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPERTIES_FILE="$PROJECT_ROOT/output.properties"

# Default port if not specified in properties
DEFAULT_PORT=4000

# Try to read port from properties file
if [ -f "$PROPERTIES_FILE" ]; then
    PORT=$(grep "^dashboard\.port=" "$PROPERTIES_FILE" | cut -d'=' -f2 | tr -d ' ')

    # Validate port is a number
    if [[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -gt 0 ] && [ "$PORT" -le 65535 ]; then
        DEFAULT_PORT=$PORT
    else
        echo "⚠️  Invalid port in output.properties, using default 4000"
        DEFAULT_PORT=4000
    fi
fi

PORT=$DEFAULT_PORT

# Check if port is available, if not try next ports
while [ $PORT -lt $((DEFAULT_PORT + 10)) ]; do
    if lsof -ti:$PORT > /dev/null 2>&1; then
        echo "Port $PORT is in use, trying next port..."
        PORT=$((PORT + 1))
    else
        break
    fi
done

if [ $PORT -ne $DEFAULT_PORT ]; then
    echo "⚠️  Port $DEFAULT_PORT unavailable, using port $PORT"
else
    echo "✓ Starting dashboard on port $PORT"
fi

cd "$(dirname "$0")"
PORT=$PORT npm run dev
