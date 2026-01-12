'use client';

import React from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

interface ResourceSnapshot {
  timestamp: number;
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryUsagePercent: number;
  gpuUsagePercent: number;
  gpuMemoryUsedMb: number;
  gpuMemoryTotalMb: number;
}

interface SystemMonitorProps {
  cpuUsagePercent: number;
  memoryUsedMb: number;
  memoryTotalMb: number;
  memoryUsagePercent: number;
  gpuUsagePercent: number;
  gpuMemoryUsedMb: number;
  gpuMemoryTotalMb: number;
  resourceHistory?: ResourceSnapshot[];
}

export default function SystemMonitor({
  cpuUsagePercent = 0,
  memoryUsedMb = 0,
  memoryTotalMb = 0,
  memoryUsagePercent = 0,
  gpuUsagePercent = 0,
  gpuMemoryUsedMb = 0,
  gpuMemoryTotalMb = 0,
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

  // GPU 게이지 색상
  const getGpuColor = (usage: number) => {
    if (usage < 50) return 'from-emerald-400 to-emerald-500';
    if (usage < 80) return 'from-orange-400 to-orange-500';
    return 'from-red-400 to-red-500';
  };

  // GPU 메모리 사용률 계산
  const gpuMemoryPercent = gpuMemoryTotalMb > 0
    ? (gpuMemoryUsedMb / gpuMemoryTotalMb * 100)
    : 0;

  // 차트 데이터 준비 (최근 50개만)
  const chartData = (resourceHistory && resourceHistory.length > 0)
    ? resourceHistory.slice(-50).map((snapshot) => ({
        time: new Date(snapshot.timestamp).toLocaleTimeString(),
        cpu: Number(snapshot.cpuUsagePercent?.toFixed(1) || 0),
        memory: Number(snapshot.memoryUsagePercent?.toFixed(1) || 0),
        gpu: Number(snapshot.gpuUsagePercent?.toFixed(1) || 0),
      }))
    : [];

  return (
    <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
      {/* CPU Monitor */}
      <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20 hover:border-purple-400/40 transition-all">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <span className="text-xl">🖥️</span>
            <span className="text-sm text-gray-400">CPU</span>
          </div>
          <div className="text-xl font-bold">
            {cpuUsagePercent.toFixed(1)}%
          </div>
        </div>

        {/* CPU Progress Bar */}
        <div className="bg-slate-700 rounded-full h-3 overflow-hidden">
          <div
            className={`bg-gradient-to-r ${getCpuColor(cpuUsagePercent)} h-full transition-all duration-500`}
            style={{ width: `${Math.min(cpuUsagePercent, 100)}%` }}
          >
            <div className="w-full h-full animate-pulse opacity-50"></div>
          </div>
        </div>

        {/* CPU Status */}
        <div className="mt-1 text-xs text-gray-500 text-center">
          {cpuUsagePercent < 50 && '⚡ Normal'}
          {cpuUsagePercent >= 50 && cpuUsagePercent < 80 && '⚠️ High'}
          {cpuUsagePercent >= 80 && '🔥 Very High'}
        </div>
      </div>

      {/* Memory Monitor */}
      <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20 hover:border-purple-400/40 transition-all">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <span className="text-xl">💾</span>
            <span className="text-sm text-gray-400">Memory</span>
          </div>
          <div className="text-xl font-bold">
            {memoryUsagePercent.toFixed(1)}%
          </div>
        </div>

        {/* Memory Progress Bar */}
        <div className="bg-slate-700 rounded-full h-3 overflow-hidden">
          <div
            className={`bg-gradient-to-r ${getMemColor(memoryUsagePercent)} h-full transition-all duration-500`}
            style={{ width: `${Math.min(memoryUsagePercent, 100)}%` }}
          >
            <div className="w-full h-full animate-pulse opacity-50"></div>
          </div>
        </div>

        {/* Memory Details */}
        <div className="mt-1 text-xs text-gray-500 text-center">
          {memoryUsedMb.toLocaleString()} / {memoryTotalMb.toLocaleString()} MB
        </div>
      </div>

      {/* GPU Monitor */}
      <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-emerald-500/20 hover:border-emerald-400/40 transition-all">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <span className="text-xl">🎮</span>
            <span className="text-sm text-gray-400">GPU</span>
          </div>
          <div className="text-xl font-bold text-emerald-400">
            {gpuUsagePercent.toFixed(1)}%
          </div>
        </div>

        {/* GPU Progress Bar */}
        <div className="bg-slate-700 rounded-full h-3 overflow-hidden">
          <div
            className={`bg-gradient-to-r ${getGpuColor(gpuUsagePercent)} h-full transition-all duration-500`}
            style={{ width: `${Math.min(gpuUsagePercent, 100)}%` }}
          >
            <div className="w-full h-full animate-pulse opacity-50"></div>
          </div>
        </div>

        {/* GPU Memory Details */}
        <div className="mt-1 text-xs text-gray-500 text-center">
          {(gpuMemoryUsedMb / 1024).toFixed(1)} / {(gpuMemoryTotalMb / 1024).toFixed(1)} GB
        </div>
      </div>

      {/* Resource History Chart */}
      {chartData.length > 0 ? (
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20 lg:col-span-1">
          <div className="flex items-center gap-2 mb-2">
            <span className="text-lg">📈</span>
            <span className="text-sm text-gray-400">History</span>
          </div>
          <ResponsiveContainer width="100%" height={80}>
            <LineChart data={chartData}>
              <XAxis dataKey="time" hide />
              <YAxis hide domain={[0, 100]} />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#1e293b',
                  border: '1px solid #475569',
                  borderRadius: '8px',
                  fontSize: '12px',
                }}
                labelStyle={{ color: '#e5e7eb' }}
              />
              <Line
                type="monotone"
                dataKey="cpu"
                name="CPU"
                stroke="#22d3ee"
                strokeWidth={2}
                dot={false}
                animationDuration={300}
              />
              <Line
                type="monotone"
                dataKey="memory"
                name="Mem"
                stroke="#a78bfa"
                strokeWidth={2}
                dot={false}
                animationDuration={300}
              />
              <Line
                type="monotone"
                dataKey="gpu"
                name="GPU"
                stroke="#34d399"
                strokeWidth={2}
                dot={false}
                animationDuration={300}
              />
            </LineChart>
          </ResponsiveContainer>
          <div className="flex justify-center gap-3 text-xs text-gray-500 mt-1">
            <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-full bg-cyan-400"></span>CPU</span>
            <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-full bg-purple-400"></span>Mem</span>
            <span className="flex items-center gap-1"><span className="w-2 h-2 rounded-full bg-emerald-400"></span>GPU</span>
          </div>
        </div>
      ) : (
        <div className="bg-slate-800/50 backdrop-blur-sm rounded-lg p-4 border border-purple-500/20 flex items-center justify-center">
          <span className="text-sm text-gray-500">No history data</span>
        </div>
      )}
    </div>
  );
}
