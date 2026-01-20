'use client';

import React from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, AreaChart, Area } from 'recharts';

interface LlmResponseTimeSnapshot {
  timestamp: number;
  durationMs: number;
  success: boolean;
  methodName: string;
}

interface LlmMonitorProps {
  totalLlmRequests: number;
  successfulLlmRequests: number;
  failedLlmRequests: number;
  averageLlmResponseTime: number;
  llmFailureRate: number;
  llmResponseTimeHistory?: LlmResponseTimeSnapshot[];
}

export default function LlmMonitor({
  totalLlmRequests = 0,
  successfulLlmRequests = 0,
  failedLlmRequests = 0,
  averageLlmResponseTime = 0,
  llmFailureRate = 0,
  llmResponseTimeHistory = [],
}: LlmMonitorProps) {
  // 성공률 계산
  const successRate = totalLlmRequests > 0
    ? (successfulLlmRequests / totalLlmRequests * 100)
    : 0;

  // 실패율 색상
  const getFailureRateColor = (rate: number) => {
    if (rate < 5) return 'from-green-400 to-green-500';
    if (rate < 15) return 'from-yellow-400 to-yellow-500';
    return 'from-red-400 to-red-500';
  };

  // 응답 시간 색상
  const getResponseTimeColor = (ms: number) => {
    if (ms < 1000) return 'from-green-400 to-green-500';
    if (ms < 3000) return 'from-blue-400 to-blue-500';
    if (ms < 5000) return 'from-yellow-400 to-yellow-500';
    return 'from-red-400 to-red-500';
  };

  // 차트 데이터 준비 (응답 시간)
  const responseTimeChartData = (llmResponseTimeHistory && llmResponseTimeHistory.length > 0)
    ? llmResponseTimeHistory.slice(-100).map((snapshot) => ({
        time: new Date(snapshot.timestamp).toLocaleTimeString(),
        responseTime: snapshot.durationMs,
        success: snapshot.success,
      }))
    : [];

  // 성공/실패 추이 데이터
  const failureRateChartData = responseTimeChartData.map((item) => ({
    time: item.time,
    failureRate: llmFailureRate * 100, // 현재 실패율을 표시
  }));

  return (
    <div className="space-y-4">
      {/* 통계 카드 */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 총 요청 */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-blue-500/20 hover:border-blue-400/40 transition-all">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm text-gray-400">총 요청</span>
            <span className="text-lg">📊</span>
          </div>
          <div className="text-2xl font-bold text-blue-400">{totalLlmRequests}</div>
        </div>

        {/* 성공 */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-green-500/20 hover:border-green-400/40 transition-all">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm text-gray-400">성공</span>
            <span className="text-lg">✅</span>
          </div>
          <div className="text-2xl font-bold text-green-400">{successfulLlmRequests}</div>
          <div className="text-xs text-gray-500 mt-1">{successRate.toFixed(1)}%</div>
        </div>

        {/* 실패 */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-red-500/20 hover:border-red-400/40 transition-all">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm text-gray-400">실패</span>
            <span className="text-lg">❌</span>
          </div>
          <div className="text-2xl font-bold text-red-400">{failedLlmRequests}</div>
          <div className="text-xs text-gray-500 mt-1">{(llmFailureRate * 100).toFixed(1)}%</div>
        </div>

        {/* 평균 응답 시간 */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20 hover:border-purple-400/40 transition-all">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm text-gray-400">평균 시간</span>
            <span className="text-lg">⏱️</span>
          </div>
          <div className={`text-2xl font-bold bg-gradient-to-r ${getResponseTimeColor(averageLlmResponseTime)} bg-clip-text text-transparent`}>
            {averageLlmResponseTime.toFixed(0)}ms
          </div>
        </div>
      </div>

      {/* 그래프 1행 2열 레이아웃 */}
      {responseTimeChartData.length > 0 && (
        <div className="grid grid-cols-2 gap-4">
          {/* 응답 시간 그래프 */}
          <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20">
            <h3 className="text-sm font-semibold text-gray-400 mb-4">LLM 응답 시간 (ms)</h3>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={responseTimeChartData}>
                <XAxis
                  dataKey="time"
                  stroke="#94a3b8"
                  fontSize={12}
                  tickFormatter={(value) => value.slice(3, 8)} // HH:mm만 표시
                />
                <YAxis
                  stroke="#94a3b8"
                  fontSize={12}
                  label={{ value: '시간 (ms)', position: 'insideLeft', angle: -90, fill: '#94a3b8' }}
                />
                <Tooltip
                  contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: '8px' }}
                  formatter={(value: any, name: any, props: any) => {
                    if (name === 'responseTime') {
                      return `${value}ms`;
                    }
                    return value;
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="responseTime"
                  stroke="#a855f7"
                  fill="#a855f7"
                  fillOpacity={0.3}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>

          {/* 실패율 그래프 */}
          <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-red-500/20">
            <h3 className="text-sm font-semibold text-gray-400 mb-4">실패율 (%)</h3>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={failureRateChartData}>
                <XAxis
                  dataKey="time"
                  stroke="#94a3b8"
                  fontSize={12}
                  tickFormatter={(value) => value.slice(3, 8)}
                />
                <YAxis
                  stroke="#94a3b8"
                  fontSize={12}
                  domain={[0, 100]}
                  label={{ value: '실패율 (%)', position: 'insideLeft', angle: -90, fill: '#94a3b8' }}
                />
                <Tooltip
                  contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: '8px' }}
                  formatter={(value: any) => `${value.toFixed(1)}%`}
                />
                <Line
                  type="monotone"
                  dataKey="failureRate"
                  stroke="#ef4444"
                  strokeWidth={2}
                  dot={{ fill: '#ef4444' }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {/* 통계 요약 */}
      <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-slate-700">
        <h3 className="text-sm font-semibold text-gray-400 mb-3">📊 통계 요약</h3>
        <div className="grid grid-cols-2 gap-4 text-xs">
          <div>
            <span className="text-gray-500">성공률:</span>
            <span className="ml-2 text-green-400 font-bold">{successRate.toFixed(1)}%</span>
          </div>
          <div>
            <span className="text-gray-500">실패율:</span>
            <span className={`ml-2 font-bold bg-gradient-to-r ${getFailureRateColor(llmFailureRate)} bg-clip-text text-transparent`}>
              {(llmFailureRate * 100).toFixed(1)}%
            </span>
          </div>
          <div>
            <span className="text-gray-500">평균 응답:</span>
            <span className={`ml-2 font-bold bg-gradient-to-r ${getResponseTimeColor(averageLlmResponseTime)} bg-clip-text text-transparent`}>
              {averageLlmResponseTime < 1000
                ? `${averageLlmResponseTime.toFixed(0)}ms`
                : `${(averageLlmResponseTime / 1000).toFixed(1)}s`
              }
            </span>
          </div>
          <div>
            <span className="text-gray-500">요청/분:</span>
            <span className="ml-2 text-blue-400 font-bold">
              {responseTimeChartData.length > 0
                ? (60 / (responseTimeChartData.length / 5)).toFixed(1) // 대략 5분치 데이터라고 가정
                : '-'}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
