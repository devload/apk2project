#!/bin/bash
# APK2Project 파이프라인 + Dashboard 실행 스크립트

set -e

# 프로젝트 루트 디렉토리
PROJECT_DIR="/Users/devload/apk2proejct_ai/apk2project"
cd "$PROJECT_DIR"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 로그 파일 디렉토리
LOG_DIR="$PROJECT_DIR/logs"
mkdir -p "$LOG_DIR"

# PID 파일
PIPELINE_PID_FILE="$LOG_DIR/pipeline.pid"
DASHBOARD_PID_FILE="$LOG_DIR/dashboard.pid"

# 포트 설정
DASHBOARD_PORT=4000

echo -e "${BLUE}======================================${NC}"
echo -e "${BLUE}APK2Project Pipeline + Dashboard${NC}"
echo -e "${BLUE}======================================${NC}"
echo ""

# 이미 실행 중인 프로세스 확인 및 종료
if [ -f "$PIPELINE_PID_FILE" ]; then
    OLD_PID=$(cat "$PIPELINE_PID_FILE")
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo -e "${YELLOW}이미 실행 중인 파이프라인 프로세스 발견 (PID: $OLD_PID)${NC}"
        read -p "종료하고 다시 시작하시겠습니까? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo -e "${YELLOW}파이프라인 프로세스 종료 중...${NC}"
            kill $OLD_PID 2>/dev/null || true
            rm -f "$PIPELINE_PID_FILE"
        else
            echo -e "${RED}실행 취소${NC}"
            exit 1
        fi
    fi
fi

if [ -f "$DASHBOARD_PID_FILE" ]; then
    OLD_PID=$(cat "$DASHBOARD_PID_FILE")
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo -e "${YELLOW}이미 실행 중인 Dashboard 프로세스 발견 (PID: $OLD_PID)${NC}"
        read -p "종료하고 다시 시작하시겠습니까? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo -e "${YELLOW}Dashboard 프로세스 종료 중...${NC}"
            kill $OLD_PID 2>/dev/null || true
            rm -f "$DASHBOARD_PID_FILE"
        else
            echo -e "${RED}실행 취소${NC}"
            exit 1
        fi
    fi
fi

# 포트가 사용 중인지 확인
if lsof -Pi :$DASHBOARD_PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${RED}포트 $DASHBOARD_PORT가 이미 사용 중입니다.${NC}"
    echo -e "${YELLOW}사용 중인 프로세스:${NC}"
    lsof -Pi :$DASHBOARD_PORT -sTCP:LISTEN
    echo ""
    read -p "다른 포트를 사용하시겠습니까? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        DASHBOARD_PORT=4001
        echo -e "${GREEN}포트 $DASHBOARD_PORT 사용 시도${NC}"
    else
        echo -e "${RED}실행 취소${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}1. Dashboard 시작 중...${NC}"
cd "$PROJECT_DIR/dashboard"

# 백그라운드로 Dashboard 실행
nohup npm run dev -- --port $DASHBOARD_PORT > "$LOG_DIR/dashboard.log" 2>&1 &
DASHBOARD_PID=$!
echo $DASHBOARD_PID > "$DASHBOARD_PID_FILE"

# Dashboard 시작 대기
echo -e "${YELLOW}Dashboard 시작 대기 중...${NC}"
sleep 5

# Dashboard 실행 확인
if ps -p $DASHBOARD_PID > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Dashboard 실행 중 (PID: $DASHBOARD_PID)${NC}"
    echo -e "${GREEN}  URL: http://localhost:$DASHBOARD_PORT${NC}"
    echo -e "${GREEN}  로그: $LOG_DIR/dashboard.log${NC}"
else
    echo -e "${RED}✗ Dashboard 시작 실패${NC}"
    echo -e "${YELLOW}로그 확인: tail -f $LOG_DIR/dashboard.log${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}2. Pipeline 실행 중...${NC}"
cd "$PROJECT_DIR"

# 백그라운드로 파이프라인 실행
APK_PATH="/Users/devload/whatap/apkInjector/cleanapk/hanacard/base.apk"
OUTPUT_DIR="hanacard-output"

nohup ./gradlew run --args="generate $APK_PATH --output $OUTPUT_DIR --ai --model deepseek-coder:6.7b" > "$LOG_DIR/pipeline.log" 2>&1 &
PIPELINE_PID=$!
echo $PIPELINE_PID > "$PIPELINE_PID_FILE"

sleep 2

if ps -p $PIPELINE_PID > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Pipeline 실행 중 (PID: $PIPELINE_PID)${NC}"
    echo -e "${GREEN}  로그: $LOG_DIR/pipeline.log${NC}"
else
    echo -e "${RED}✗ Pipeline 시작 실패${NC}"
    echo -e "${YELLOW}로그 확인: tail -f $LOG_DIR/pipeline.log${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN}모두 실행 중입니다!${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo -e "${BLUE}Dashboard:${NC}    http://localhost:$DASHBOARD_PORT"
echo -e "${BLUE}Pipeline 로그:${NC} tail -f $LOG_DIR/pipeline.log"
echo -e "${BLUE}Dashboard 로그:${NC} tail -f $LOG_DIR/dashboard.log"
echo ""
echo -e "${YELLOW}프로세스 종료 방법:${NC}"
echo -e "  kill $PIPELINE_PID  # 파이프라인 종료"
echo -e "  kill $DASHBOARD_PID  # Dashboard 종료"
echo -e "  또는: ./stop-all.sh"
echo ""
echo -e "${YELLOW}또는 터미널에서 Ctrl+C를 눌러도 백그라운드 프로세스는 계속 실행됩니다.${NC}"
echo ""

# 사용자에게 로그 모니터링 옵션 제공
read -p "파이프라인 로그를 실시간으로 보시겠습니까? (Y/n): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Nn]$ ]]; then
    echo -e "${BLUE}파이프라인 로그 모니터링 시작 (종료: Ctrl+C)...${NC}"
    echo ""
    tail -f "$LOG_DIR/pipeline.log"
fi
