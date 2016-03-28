/**
 * Copyright 2016 Gash.
 *
 * This file and intellectual content is protected under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package server.edges;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import logger.Logger;
import raft.proto.Ping;
import raft.proto.Ping.ping;
import raft.proto.Work.WorkMessage;
import router.container.RoutingConf.RoutingEntry;
import server.ServerState;
import server.WorkHandler;
import server.WorkInit;

public class EdgeMonitor implements EdgeListener, Runnable {

	private EdgeList outboundEdges;
	private EdgeList inboundEdges;
	private long dt = 2000;
	private ServerState state;
	private boolean forever = true;

	public EdgeMonitor(ServerState state) {
		if (state == null)
			throw new RuntimeException("state is null");

		this.outboundEdges = new EdgeList();
		this.inboundEdges = new EdgeList();
		this.state = state;
		this.state.setEmon(this);

		if (state.getConf().getRouting() != null) {
			for (RoutingEntry e : state.getConf().getRouting()) {
				EdgeInfo ei = outboundEdges.addNode(e.getId(), e.getHost(), e.getPort());
			}
		}

		// cannot go below 2 sec
		if (state.getConf().getHeartbeatDt() > this.dt)
			this.dt = state.getConf().getHeartbeatDt();
	}

	public void createInboundIfNew(int ref, String host, int port) {
		inboundEdges.createIfNew(ref, host, port);
	}

	private ping createHB(EdgeInfo ei) {

		Ping.ping.Builder ping = Ping.ping.newBuilder();
		ping.setNodeId(state.getConf().getNodeId());
		try {
			ping.setIP(InetAddress.getLocalHost().getHostAddress());
			ping.setPort(0000);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return ping.build();
	}

	public void shutdown() {
		forever = false;
	}

	@Override
	public void run() {
		while (forever) {
			try {
				for (EdgeInfo ei : this.outboundEdges.map.values()) {
					if (ei.isActive() && ei.getChannel() != null) {
						ping ping = createHB(ei);
						Logger.DEBUG("Sent Heartbeat to " + ei.getRef());
						ChannelFuture cf = ei.getChannel().writeAndFlush(ping);
						if (cf.isDone() && !cf.isSuccess()) {
							Logger.DEBUG("failed to send message to server");
						}
					} else {
						onAdd(ei);

						// TODO create a client to the node
						Logger.DEBUG("Connection made");
					}
				}

				Thread.sleep(dt);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}
	}

	@Override
	public synchronized void onAdd(EdgeInfo ei) {
		EventLoopGroup group = new NioEventLoopGroup();
		Bootstrap b = new Bootstrap();
		b.handler(new WorkHandler(state));

		b.group(group).channel(NioSocketChannel.class).handler(new WorkInit(state, false));
		b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
		b.option(ChannelOption.TCP_NODELAY, true);
		b.option(ChannelOption.SO_KEEPALIVE, true);

		// Make the connection attempt.
		ChannelFuture cf = b.connect(ei.getHost(), ei.getPort()).syncUninterruptibly();

		ei.setChannel(cf.channel());
		ei.setActive(true);
		cf.channel().closeFuture();
	}

	@Override
	public synchronized void onRemove(EdgeInfo ei) {
		// TODO ?
	}
}