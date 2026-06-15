import React from 'react'
import Card from './Card'

export default function CommanderZone({ commandZone = [], onCardClick, playable = false }) {
  if (!commandZone.length) return null

  return (
    <div className="bg-gray-900/70 border border-yellow-900 rounded-lg p-2">
      <h4 className="text-yellow-500 text-[10px] font-bold uppercase tracking-widest mb-2">
        Command Zone
      </h4>
      <div className="flex flex-wrap gap-3">
        {commandZone.map((commander) => {
          const tax = (commander.castCount ?? 0) * 2
          return (
            <div key={commander.id} className="flex flex-col items-center gap-1">
              <Card
                card={commander}
                size="md"
                playable={playable}
                onClick={() => onCardClick?.(commander)}
              />
              <div className="flex flex-col items-center gap-0.5">
                <span className="text-yellow-300 text-[10px] font-semibold truncate max-w-[80px] text-center">
                  {commander.name}
                </span>
                {tax > 0 && (
                  <span
                    className="bg-red-900 text-red-200 text-[9px] font-bold px-1.5 rounded-full border border-red-700"
                    title={`Commander tax: +${tax} generic mana`}
                  >
                    +{tax} tax
                  </span>
                )}
                <span className="text-gray-600 text-[9px]">
                  cast {commander.castCount ?? 0}x
                </span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
