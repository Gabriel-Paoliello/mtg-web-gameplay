import React from 'react'

const PHASES = [
  { key: 'UNTAP',    label: 'Untap',   color: 'text-teal-400' },
  { key: 'UPKEEP',   label: 'Upkeep',  color: 'text-yellow-400' },
  { key: 'DRAW',     label: 'Draw',    color: 'text-blue-400' },
  { key: 'MAIN1',    label: 'Main 1',  color: 'text-green-400' },
  { key: 'COMBAT',   label: 'Combat',  color: 'text-red-400' },
  { key: 'MAIN2',    label: 'Main 2',  color: 'text-green-400' },
  { key: 'END',      label: 'End',     color: 'text-gray-400' },
]

// Normalize server phase strings
function normalizePhase(phase) {
  if (!phase) return null
  const p = phase.toUpperCase().replace(/[^A-Z0-9]/g, '')
  if (p.includes('UNTAP')) return 'UNTAP'
  if (p.includes('UPKEEP')) return 'UPKEEP'
  if (p.includes('DRAW')) return 'DRAW'
  if (p.includes('MAIN1') || p === 'PRECOMBATMAIN') return 'MAIN1'
  if (p.includes('COMBAT')) return 'COMBAT'
  if (p.includes('MAIN2') || p === 'POSTCOMBATMAIN') return 'MAIN2'
  if (p.includes('END') || p.includes('CLEANUP')) return 'END'
  return null
}

export default function TurnIndicator({
  turn,
  phase,
  activePlayer,
  priorityPlayerId,
  localPlayerId,
  onPassPriority,
  connected,
}) {
  const normPhase = normalizePhase(phase)
  const isMyPriority = priorityPlayerId && priorityPlayerId === localPlayerId

  return (
    <div className="flex flex-col items-center gap-1">
      {/* Turn number */}
      <div className="flex items-center gap-2">
        <span className="text-gray-500 text-xs uppercase tracking-widest">Turn</span>
        <span className="text-yellow-300 font-bold text-lg leading-none">{turn ?? '—'}</span>
        {activePlayer && (
          <span className="text-gray-400 text-xs">
            — {activePlayer.name}
          </span>
        )}
      </div>

      {/* Phase strip */}
      <div className="flex items-center gap-1">
        {PHASES.map(({ key, label, color }) => {
          const isActive = normPhase === key
          return (
            <div
              key={key}
              className={`px-2 py-0.5 rounded text-[10px] font-semibold transition-all
                ${isActive
                  ? `${color} bg-gray-800 border border-gray-600 scale-110`
                  : 'text-gray-600 bg-transparent'
                }`}
            >
              {label}
            </div>
          )
        })}
      </div>

      {/* Pass Priority button */}
      {isMyPriority && (
        <button
          onClick={onPassPriority}
          disabled={!connected}
          className="mt-1 px-5 py-1.5 bg-yellow-500 hover:bg-yellow-400 active:bg-yellow-600
            text-black font-bold text-sm rounded-md shadow-lg
            disabled:opacity-50 disabled:cursor-not-allowed
            transition-colors duration-100 ring-2 ring-yellow-300 animate-pulse"
        >
          Pass Priority
        </button>
      )}
    </div>
  )
}
