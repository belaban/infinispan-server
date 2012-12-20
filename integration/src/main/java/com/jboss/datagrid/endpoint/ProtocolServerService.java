/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */
package com.jboss.datagrid.endpoint;

import static com.jboss.datagrid.EndpointLogger.ROOT_LOGGER;

import java.net.InetSocketAddress;
import java.util.Properties;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.core.Main;
import org.infinispan.server.core.ProtocolServer;
import org.infinispan.server.core.transport.Transport;
import org.infinispan.util.ReflectionUtil;
import org.jboss.as.network.NetworkUtils;
import org.jboss.as.network.SocketBinding;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * The service that configures and starts the endpoints supported by data grid.
 *
 * @author Tristan Tarrant
 */
class ProtocolServerService implements Service<ProtocolServer> {
   private static final String DEFAULT_WORKER_THREADS = "160";

   // The cacheManager that will be injected by the container (specified by the cacheContainer
   // attribute)
   private final InjectedValue<EmbeddedCacheManager> cacheManager = new InjectedValue<EmbeddedCacheManager>();
   // The socketBinding that will be injected by the container
   private final InjectedValue<SocketBinding> socketBinding = new InjectedValue<SocketBinding>();
   // The configuration for this service
   private final ModelNode config;
   // Additional connector properties
   private final Properties connectorProperties = new Properties();
   // Topology state transfer properties
   private final Properties topologyStateTransferProperties = new Properties();
   // The class which determines the type of server
   private final Class<? extends ProtocolServer> serverClass;
   // The server which handles the protocol
   private ProtocolServer protocolServer;
   // The transport used by the protocol server
   private Transport transport;
   // The name of the server
   private final String serverName;

   ProtocolServerService(ModelNode config, Class<? extends ProtocolServer> serverClass) {
      this.config = config.clone();
      this.serverClass = serverClass;
      String serverTypeName = serverClass.getSimpleName();
      this.serverName = config.hasDefined(ModelKeys.NAME) ? serverTypeName + " "
            + config.get(ModelKeys.NAME).asString() : serverTypeName;
   }

   @Override
   public synchronized void start(final StartContext context) throws StartException {
      ROOT_LOGGER.endpointStarting(serverName);

      assert connectorProperties.isEmpty();
      assert topologyStateTransferProperties.isEmpty();

      boolean done = false;
      try {
         loadConnectorProperties(config);
         loadTopologyStateTransferProperties(config);
         validateConfiguration();

         // Start the connector
         startProtocolServer();

         SocketBinding binding = socketBinding.getValue();
         ROOT_LOGGER.endpointStarted(serverName, NetworkUtils.formatAddress(binding.getAddress()), binding.getAbsolutePort());

         done = true;
      } catch (StartException e) {
         throw e;
      } catch (Exception e) {
         throw ROOT_LOGGER.failedStart(e, serverName);
      } finally {
         if (!done) {
            doStop();
         }
      }
   }

   private void validateConfiguration() throws StartException {
      // There has to be at least one connector defined.
      if (connectorProperties.isEmpty()) {
         throw ROOT_LOGGER.noConnectorDefined();
      }
   }

   private void startProtocolServer() throws StartException {

      Properties props = copy(connectorProperties);
      if (props == null) {
         return;
      }

      // Merge topology state transfer settings
      props.putAll(topologyStateTransferProperties);

      // Start the server and record it
      ProtocolServer server;
      try {
         server = serverClass.newInstance();
      } catch (Exception e) {
         throw ROOT_LOGGER.failedConnectorInstantiation(e,  serverName);
      }
      ROOT_LOGGER.connectorStarting(serverName);
      server.start(props, getCacheManager().getValue());
      protocolServer = server;

      try {
         transport = (Transport) ReflectionUtil.getValue(protocolServer, "transport");
      } catch (Exception e) {
         throw ROOT_LOGGER.failedTransportInstantiation(e.getCause(), serverName);
      }
   }

   @Override
   public synchronized void stop(final StopContext context) {
      doStop();
   }

   private void doStop() {
      try {
         if (protocolServer != null) {
            ROOT_LOGGER.connectorStopping(serverName);
            try {
               protocolServer.stop();
            } catch (Exception e) {
               ROOT_LOGGER.connectorStopFailed(e, serverName);
            }
         }
      } finally {
         connectorProperties.clear();
         topologyStateTransferProperties.clear();
         ROOT_LOGGER.connectorStopped(serverName);
      }
   }

   @Override
   public synchronized ProtocolServer getValue() throws IllegalStateException {
      if (protocolServer == null) {
         throw new IllegalStateException();
      }
      return protocolServer;
   }

   InjectedValue<EmbeddedCacheManager> getCacheManager() {
      return cacheManager;
   }

   String getCacheContainerName() {
      if (!config.hasDefined(ModelKeys.CACHE_CONTAINER)) {
         return null;
      }
      return config.get(ModelKeys.CACHE_CONTAINER).asString();
   }

   String getRequiredSocketBindingName() {
      return config.hasDefined(ModelKeys.SOCKET_BINDING) ? config.get(ModelKeys.SOCKET_BINDING).asString() : null;
   }

   InjectedValue<SocketBinding> getSocketBinding() {
      return socketBinding;
   }

   private void loadConnectorProperties(ModelNode config) {
      if (config.hasDefined(ModelKeys.SOCKET_BINDING)) {
         SocketBinding socketBinding = getSocketBinding().getValue();
         InetSocketAddress socketAddress = socketBinding.getSocketAddress();
         connectorProperties.setProperty(Main.PROP_KEY_HOST(), socketAddress.getAddress().getHostAddress());
         connectorProperties.setProperty(Main.PROP_KEY_PORT(), String.valueOf(socketAddress.getPort()));
      }
      if (config.hasDefined(ModelKeys.WORKER_THREADS)) {
         connectorProperties.setProperty(Main.PROP_KEY_WORKER_THREADS(), config.get(ModelKeys.WORKER_THREADS)
               .asString());
      } else {
         connectorProperties.setProperty(Main.PROP_KEY_WORKER_THREADS(), DEFAULT_WORKER_THREADS);
      }
      if (config.hasDefined(ModelKeys.IDLE_TIMEOUT)) {
         connectorProperties.setProperty(Main.PROP_KEY_IDLE_TIMEOUT(), config.get(ModelKeys.IDLE_TIMEOUT).asString());
      }
      if (config.hasDefined(ModelKeys.TCP_NODELAY)) {
         connectorProperties.setProperty(Main.PROP_KEY_TCP_NO_DELAY(), config.get(ModelKeys.TCP_NODELAY).asString());
      }
      if (config.hasDefined(ModelKeys.SEND_BUFFER_SIZE)) {
         connectorProperties.setProperty(Main.PROP_KEY_SEND_BUF_SIZE(), config.get(ModelKeys.SEND_BUFFER_SIZE)
               .asString());
      }
      if (config.hasDefined(ModelKeys.RECEIVE_BUFFER_SIZE)) {
         connectorProperties.setProperty(Main.PROP_KEY_RECV_BUF_SIZE(), config.get(ModelKeys.RECEIVE_BUFFER_SIZE)
               .asString());
      }

   }

   private void loadTopologyStateTransferProperties(ModelNode config) {
      if (!config.hasDefined(ModelKeys.TOPOLOGY_STATE_TRANSFER)) {
         return;
      }

      config = config.get(ModelKeys.TOPOLOGY_STATE_TRANSFER);
      if (config.hasDefined(ModelKeys.LOCK_TIMEOUT)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_TOPOLOGY_LOCK_TIMEOUT(),
               config.get(ModelKeys.LOCK_TIMEOUT).asString());
      }
      if (config.hasDefined(ModelKeys.REPLICATION_TIMEOUT)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_TOPOLOGY_REPL_TIMEOUT(),
               config.get(ModelKeys.REPLICATION_TIMEOUT).asString());
      }
      if (config.hasDefined(ModelKeys.UPDATE_TIMEOUT)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_TOPOLOGY_UPDATE_TIMEOUT(),
               config.get(ModelKeys.UPDATE_TIMEOUT).asString());
      }
      if (config.hasDefined(ModelKeys.EXTERNAL_HOST)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_PROXY_HOST(), config.get(ModelKeys.EXTERNAL_HOST)
               .asString());
      }
      if (config.hasDefined(ModelKeys.EXTERNAL_PORT)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_PROXY_PORT(), config.get(ModelKeys.EXTERNAL_PORT)
               .asString());
      }
      if (config.hasDefined(ModelKeys.LAZY_RETRIEVAL)) {
         topologyStateTransferProperties.setProperty(Main.PROP_KEY_TOPOLOGY_STATE_TRANSFER(),
               Boolean.toString(!config.get(ModelKeys.LAZY_RETRIEVAL).asBoolean(false)));
      }
   }

   private static Properties copy(Properties p) {
      if (p == null) {
         return null;
      }
      Properties newProps = new Properties();
      newProps.putAll(p);
      return newProps;
   }

   public Transport getTransport() {
      return transport;
   }
}