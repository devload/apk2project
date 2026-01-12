#!/bin/bash
# Dashboard start script with automatic port selection

DEFAULT_PORT=4000
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
