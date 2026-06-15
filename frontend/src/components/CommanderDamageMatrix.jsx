import React from 'react'

/**
 * Commander damage matrix: rows = defenders (players), cols = attacker commanders
 * Each cell shows how much damage that commander has dealt to that player.
 */
export default function CommanderDamageMatrix({ players = [] }) {
  // Collect all commanders across all players
  const allCommanders = players.flatMap((p) =>
    (p.commandZone ?? []).map((c) => ({ ...c, ownerId: p.id }))
  )

  if (allCommanders.length === 0) return null

  return (
    <div className="bg-gray-900/80 border border-yellow-900/50 rounded-lg p-3">
      <h4 className="text-yellow-500 text-[10px] font-bold uppercase tracking-widest mb-2">
        Commander Damage
      </h4>
      <table className="text-xs w-full border-collapse">
        <thead>
          <tr>
            <th className="text-left text-gray-600 font-normal pb-1 pr-2">Player</th>
            {allCommanders.map((c) => (
              <th
                key={c.id}
                className="text-center text-gray-500 font-normal pb-1 px-1 max-w-[60px] truncate"
                title={c.name}
              >
                <span className="block truncate max-w-[60px]">{c.name}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {players.map((player) => (
            <tr key={player.id} className="border-t border-gray-800">
              <td className="text-gray-400 py-1 pr-2 truncate max-w-[80px]" title={player.name}>
                {player.name}
              </td>
              {allCommanders.map((c) => {
                const dmg = player.commanderDamage?.[c.id] ?? 0
                return (
                  <td
                    key={c.id}
                    className={`text-center py-1 px-1 font-bold
                      ${dmg >= 21 ? 'text-red-400 bg-red-950/40'
                        : dmg >= 15 ? 'text-orange-400'
                        : dmg > 0 ? 'text-yellow-300'
                        : 'text-gray-700'}`}
                  >
                    {dmg}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-[10px] text-gray-700 mt-1">21+ = lethal</p>
    </div>
  )
}
