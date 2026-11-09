#!/bin/bash
# 더블클릭으로 실행 (처음 한 번: 우클릭 → 열기)
cd "$(dirname "$0")"
echo "필요한 부품 확인 중..."
python3 -m pip install --quiet --user pyobjc-framework-Quartz pyobjc-framework-Cocoa pyperclip
python3 client.py
