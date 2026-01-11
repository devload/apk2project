import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';

export async function GET() {
  try {
    // status.json 파일 경로 - 실제 위치는 hana-decompiled/sources/.apk2project/status.json
    const statusPath = path.join(process.cwd(), '..', 'hana-decompiled', 'sources', '.apk2project', 'status.json');

    // 파일이 존재하는지 확인
    if (!fs.existsSync(statusPath)) {
      return NextResponse.json(
        { error: 'Status file not found' },
        { status: 404 }
      );
    }

    // 파일 읽기
    const data = fs.readFileSync(statusPath, 'utf-8');
    const status = JSON.parse(data);

    // CORS 헤더 추가
    return NextResponse.json(status, {
      headers: {
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache',
        'Expires': '0',
      },
    });
  } catch (error) {
    console.error('Error reading status.json:', error);
    return NextResponse.json(
      { error: 'Failed to read status file' },
      { status: 500 }
    );
  }
}
