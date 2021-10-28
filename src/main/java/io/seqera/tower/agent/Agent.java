/*
 * Copyright (c) 2021, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.tower.agent;

import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.rxjava2.http.client.websockets.RxWebSocketClient;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.websocket.exceptions.WebSocketClientException;
import io.seqera.tower.agent.exchange.HeartbeatMessage;
import io.seqera.tower.agent.utils.VersionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Command(
        name = "tw-agent",
        description = "Nextflow Tower Agent",
        headerHeading = "%n",
        versionProvider = VersionProvider.class,
        mixinStandardHelpOptions = true,
        sortOptions = false,
        abbreviateSynopsis = true,
        descriptionHeading = "%n",
        commandListHeading = "%nCommands:%n",
        requiredOptionMarker = '*',
        usageHelpWidth = 160,
        parameterListHeading = "%nParameters:%n",
        optionListHeading = "%nOptions:%n"
)
public class Agent implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(Agent.class);

    @Parameters(index = "0", paramLabel = "AGENT_CONNECTION_ID", description = "Agent connection ID to identify this agent", arity = "1")
    String agentKey;

    @Option(names = {"-t", "--access-token"}, description = "Tower personal access token (TOWER_ACCESS_TOKEN)", defaultValue = "${TOWER_ACCESS_TOKEN}")
    String token;

    @Option(names = {"-u", "--url"}, description = "Tower server API endpoint URL. Defaults to tower.nf (TOWER_API_ENDPOINT)", defaultValue = "${TOWER_API_ENDPOINT:-https://api.tower.nf}", required = true)
    String url;

    private ApplicationContext ctx;
    private AgentClientSocket agentClient;

    Agent() {
        ctx = ApplicationContext.run();
    }

    public static void main(String[] args) throws Exception {
        PicocliRunner.run(Agent.class, args);
    }

    @Override
    public void run() {

        final URI uri;
        try {
            uri = new URI(url + "/agent/" + agentKey + "/connect");
            final MutableHttpRequest<?> req = HttpRequest.GET(uri).bearerAuth(token);
            final RxWebSocketClient webSocketClient = ctx.getBean(RxWebSocketClient.class);
            agentClient = webSocketClient.connect(AgentClientSocket.class, req).blockingFirst();
            logger.info("Connected");

            sendPeriodicHeartbeat();
        } catch (URISyntaxException e) {
            logger.error(String.format("Invalid URI: %s/agent/%s/connect - %s", url, agentKey, e.getMessage()));
            System.exit(-1);
        } catch (WebSocketClientException e) {
            logger.error(String.format("Connection error - %s", e.getMessage()));
            System.exit(-1);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * Send a heartbeat every minute in order to avoid closing the connection due to idleness.
     */
    private void sendPeriodicHeartbeat() {
        TaskScheduler scheduler = ctx.getBean(TaskScheduler.class);

        scheduler.scheduleAtFixedRate(null, Duration.ofMinutes(1), () -> {
            System.out.println("Sending heartbeat");
            agentClient.send(new HeartbeatMessage());
        });
    }
}
