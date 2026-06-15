import React, { createContext, useContext } from 'react'
import { useGameSocket } from '../hooks/useGameSocket'

const GameSocketContext = createContext(null)

export function GameSocketProvider({ children }) {
  const socket = useGameSocket()
  return (
    <GameSocketContext.Provider value={socket}>
      {children}
    </GameSocketContext.Provider>
  )
}

export function useSocket() {
  return useContext(GameSocketContext)
}
