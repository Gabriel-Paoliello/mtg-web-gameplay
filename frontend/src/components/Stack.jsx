import React from 'react'

export default function Stack({ stack = [], players = [] }) {
  if (!stack.length) return null

  const getPlayerName = (id) => {
    const p = players.find((pl) => pl.id === id)
    return p?.name ?? id ?? 'Unknown'
  }

  return (
    <div className="flex flex-col gap-1 w-56">
      <h3 className="text-yellow-400 text-xs font-bold uppercase tracking-widest mb-1 px-1">
        Stack ({stack.length})
      </h3>
      <p className="text-gray-500 text-[10px] px-1 mb-1 italic">Top resolves first</p>
      {[...stack].reverse().map((item, idx) => (
        <div
          key={item.id ?? idx}
          className={`bg-gray-900 border rounded-md px-2 py-1.5 text-xs
            ${idx === 0 ? 'border-yellow-600 bg-yellow-950/30' : 'border-gray-700'}`}
        >
          <div className="flex items-center justify-between gap-1">
            <span className="font-semibold text-gray-100 truncate">{item.name}</span>
            {idx === 0 && (
              <span className="text-[9px] text-yellow-400 font-bold shrink-0">NEXT</span>
            )}
          </div>
          <div className="text-gray-500 text-[10px] mt-0.5">
            {getPlayerName(item.controllerId)}
          </div>
          {item.targets && item.targets.length > 0 && (
            <div className="text-gray-400 text-[10px] mt-0.5">
              <span className="text-gray-600">targets: </span>
              {item.targets.join(', ')}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
