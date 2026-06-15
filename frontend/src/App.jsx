import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { GameSocketProvider } from './contexts/GameSocketContext'
import Lobby from './pages/Lobby'
import Game from './pages/Game'

export default function App() {
  return (
    <BrowserRouter>
      <GameSocketProvider>
        <Routes>
          <Route path="/" element={<Lobby />} />
          <Route path="/game/:gameId" element={<Game />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </GameSocketProvider>
    </BrowserRouter>
  )
}
