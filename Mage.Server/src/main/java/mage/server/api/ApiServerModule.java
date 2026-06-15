package mage.server.api;

import mage.server.game.GameController;
import mage.server.managers.GameManager;
import mage.server.managers.ManagerFactory;
import org.apache.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.util.Map;
import java.util.UUID;

/**
 * Initializes and owns the embedded Jetty HTTP+WebSocket server that provides
 * the REST/WS bridge alongside the existing JBoss Remoting transport.
 *
 * Call {@link #init(ManagerFactory, int)} once from {@code Main.main()} after the
 * JBoss server has started.  The WS endpoint will be available at
 * {@code ws://<host>:<wsPort>/ws/game/*}.
 */
public final class ApiServerModule {

    private static final Logger logger = Logger.getLogger(ApiServerModule.class);

    /** Default WebSocket API port (separate from the JBoss bisocket port). */
    public static final int DEFAULT_WS_PORT = 17172;

    private static Server jettyServer;
    private static GameManager gameManagerRef;
    private static ManagerFactory managerFactoryRef;

    private ApiServerModule() {}

    /**
     * Start the embedded Jetty server on {@code wsPort} and register the
     * WebSocket servlet under {@code /ws/game/*}.
     *
     * @param managerFactory reference to the live ManagerFactory
     * @param wsPort         TCP port to bind (use {@link #DEFAULT_WS_PORT} if unsure)
     */
    public static void init(ManagerFactory managerFactory, int wsPort) {
        managerFactoryRef = managerFactory;
        gameManagerRef = managerFactory.gameManager();

        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setPort(wsPort);
        jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        ServletHolder wsServlet = new ServletHolder(new WebSocketGameServer());
        context.addServlet(wsServlet, "/ws/game/*");

        jettyServer.setHandler(context);

        try {
            jettyServer.start();
            logger.info("XMage WebSocket API started on port " + wsPort
                    + "  endpoint: ws://localhost:" + wsPort + "/ws/game/{gameId}");
        } catch (Exception e) {
            logger.error("Failed to start WebSocket API server on port " + wsPort, e);
        }
    }

    /**
     * Overload kept for backwards compatibility — callers that only have a
     * GameManager can still compile.  ManagerFactory-aware features will not be
     * available when using this overload.
     *
     * @deprecated Prefer {@link #init(ManagerFactory, int)}.
     */
    @Deprecated
    public static void init(GameManager gameManager, int wsPort) {
        gameManagerRef = gameManager;

        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setPort(wsPort);
        jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        ServletHolder wsServlet = new ServletHolder(new WebSocketGameServer());
        context.addServlet(wsServlet, "/ws/game/*");

        jettyServer.setHandler(context);

        try {
            jettyServer.start();
            logger.info("XMage WebSocket API started on port " + wsPort
                    + "  endpoint: ws://localhost:" + wsPort + "/ws/game/{gameId}");
        } catch (Exception e) {
            logger.error("Failed to start WebSocket API server on port " + wsPort, e);
        }
    }

    /**
     * Stop the embedded Jetty server gracefully (e.g. on JVM shutdown).
     */
    public static void stop() {
        if (jettyServer != null && jettyServer.isRunning()) {
            try {
                jettyServer.stop();
            } catch (Exception e) {
                logger.error("Error stopping WebSocket API server", e);
            }
        }
    }

    /**
     * Look up the live GameController for a game by its UUID.
     *
     * The GameManager interface exposes {@code getGameController()} which
     * returns the full map; we just look up by key.
     *
     * @param gameId game UUID
     * @return the GameController, or {@code null} if not found
     */
    public static GameController getGameController(UUID gameId) {
        if (gameManagerRef == null) {
            return null;
        }
        Map<UUID, GameController> controllers = gameManagerRef.getGameController();
        return (controllers != null) ? controllers.get(gameId) : null;
    }

    /**
     * Return the ManagerFactory registered with this module, or {@code null} if
     * the deprecated {@link #init(GameManager, int)} overload was used.
     */
    public static ManagerFactory getManagerFactory() {
        return managerFactoryRef;
    }

    /**
     * Return the GameManager registered with this module.
     */
    public static GameManager getGameManager() {
        return gameManagerRef;
    }
}
