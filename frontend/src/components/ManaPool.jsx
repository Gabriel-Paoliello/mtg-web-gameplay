import React from 'react'

const MANA_CONFIG = [
  { key: 'W', label: 'W', bg: 'bg-yellow-100', text: 'text-yellow-900', border: 'border-yellow-300', title: 'White' },
  { key: 'U', label: 'U', bg: 'bg-blue-600',   text: 'text-white',      border: 'border-blue-400',   title: 'Blue' },
  { key: 'B', label: 'B', bg: 'bg-gray-800',   text: 'text-gray-200',   border: 'border-gray-600',   title: 'Black' },
  { key: 'R', label: 'R', bg: 'bg-red-700',    text: 'text-white',      border: 'border-red-400',    title: 'Red' },
  { key: 'G', label: 'G', bg: 'bg-green-700',  text: 'text-white',      border: 'border-green-400',  title: 'Green' },
  { key: 'C', label: 'C', bg: 'bg-gray-500',   text: 'text-white',      border: 'border-gray-300',   title: 'Colorless' },
]

export default function ManaPool({ manaPool = {} }) {
  const hasAnyMana = MANA_CONFIG.some((m) => (manaPool[m.key] ?? 0) > 0)

  return (
    <div className="flex items-center gap-1">
      {!hasAnyMana && (
        <span className="text-xs text-gray-600 italic">empty</span>
      )}
      {MANA_CONFIG.map(({ key, label, bg, text, border, title }) => {
        const count = manaPool[key] ?? 0
        if (count === 0) {
          return (
            <div
              key={key}
              title={`${title} (0)`}
              className="mana-symbol bg-gray-800 text-gray-600 border border-gray-700 opacity-40"
            >
              {label}
            </div>
          )
        }
        return (
          <div key={key} className="relative" title={`${title} (${count})`}>
            <div className={`mana-symbol ${bg} ${text} border ${border}`}>
              {label}
            </div>
            {count > 1 && (
              <span className="absolute -top-1 -right-1 bg-yellow-400 text-black text-[9px] font-bold rounded-full w-4 h-4 flex items-center justify-center leading-none">
                {count}
              </span>
            )}
          </div>
        )
      })}
    </div>
  )
}
