'use client';

import React from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';

interface ResourceSnapshot {
  timestamp: number;
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryUsagePercent: number;
}

interface SystemMonitorProps {
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryTotalMb: number;
  memoryUsagePercent: number;
  resourceHistory?: ResourceSnapshot[];
}

export default function SystemMonitor({
  cpuUsagePercent = 0,
  memoryUsedMb = 0,
  memoryTotalMb = 0,
  memoryUsagePercent = 0,
  resourceHistory = [],
}: SystemMonitorProps) {
  // CPU 게이지 색상
  const getCpuColor = (usage: number) => {
    if (usage < 50) return 'from-green-400 to-green-500';
    if (usage < 80) return 'from-yellow-400 to-yellow-500';
    return 'from-red-400 to-red-500';
  };

  // RAM 게이지 색상
  const getMemColor = (usage: number) => {
    if (usage < 60) return 'from-blue-400 to-blue-500';
    if (usage < 85) return 'from-yellow-400 to-yellow-500';
    return 'from-red-400 to-red-500';
  };

  // 차트 데이터 준비 (최근 50개만)
  const chartData = (resourceHistory && resourceHistory.length > 0)
    ? resourceHistory.slice(-50).map((snapshot) => ({
        time: new Date(snapshot.timestamp).toLocaleTimeString(),
        cpu: Number(snapshot.cpuUsagePercent.toFixed(1)),
        memory: Number(snapshot.memoryUsagePercent.toFixed(1)),
      }))
    : [];

  return (
    <div className="space-y-4">
      {/* Current Stats */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* CPU Monitor */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 border border-purple-500/20 hover:border-purple-400/40 transition-all">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <span className="text-2xl">🖥️</span>
              <span className="text-sm text-gray-400">CPU Usage</span>
            </div>
            <div className="text-2xl font-bold">
              {cpuUsagePercent.toFixed(1)}%
            </div>
          </div>

          {/* CPU Progress Bar */}
          <div className="bg-slate-700 rounded-full h-4 overflow-hidden">
            <div
              className={`bg-gradient-to-r ${getCpuColor(cpuUsagePercent)} h-full transition-all duration-500`}
              style={{ width: `${Math.min(cpuUsagePercent, 100)}%` }}
            >
              <div className="w-full h-full animate-pulse opacity-50"></div>
            </div>
          </div>

          {/* CPU Status */}
          <div className="mt-2 text-xs text-gray-500 text-center">
            {cpuUsagePercent < 50 && '⚡ Normal'}
            {cpuUsagePercent >= 50 && cpuUsagePercent < 80 && '⚠️ High'}
            {cpuUsagePercent >= 80 && '🔥 Very High'}
          </div>
        </div>

        {/* Memory Monitor */}
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 border border-purple-500/20 hover:border-purple-400/40 transition-all">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <span className="text-2xl">💾</span>
              <span className="text-sm text-gray-400">Memory Usage</span>
            </div>
            <div className="text-2xl font-bold">
              {memoryUsagePercent.toFixed(1)}%
            </div>
          </div>

          {/* Memory Progress Bar */}
          <div className="bg-slate-700 rounded-full h-4 overflow-hidden">
            <div
              className={`bg-gradient-to-r ${getMemColor(memoryUsagePercent)} h-full transition-all duration-500`}
              style={{ width: `${Math.min(memoryUsagePercent, 100)}%` }}
            >
              <div className="w-full h-full animate-pulse opacity-50"></div>
            </div>
          </div>

          {/* Memory Details */}
          <div className="mt-2 text-xs text-gray-500 text-center">
            {memoryUsedMb.toLocaleString()} MB / {memoryTotalMb.toLocaleString()} MB
          </div>
        </div>
      </div>

      {/* Resource History Chart */}
      {chartData.length > 0 && (
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-6 border border-purple-500/20">
          <h3 className="text-lg font-semibold mb-4 flex items-center gap-2">
            <span>📈</span> Resource Usage History
          </h3>
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis
                dataKey="time"
                stroke="#9ca3af"
                tick={{ fontSize: 12 }}
                interval="preserveStartEnd"
              />
              <YAxis
                stroke="#9ca3af"
                tick={{ fontSize: 12 }}
                domain={[0, 100]}
                label={{ value: 'Usage (%)', angle: -90, position: 'insideLeft', fill: '#9ca3af' }}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#1e293b',
                  border: '1px solid #475569',
                  borderRadius: '8px',
                }}
                labelStyle={{ color: '#e5e7eb' }}
              />
              <Legend />
              <Line
                type="monotone"
                dataKey="cpu"
                name="CPU %"
                stroke="#22d3ee"
                strokeWidth={2}
                dot={false}
                animationDuration={300}
              />
              <Line
                type="monotone"
                dataKey="memory"
                name="Memory %"
                stroke="#a78bfa"
                strokeWidth={2}
                dot={false}
                animationDuration={300}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}
