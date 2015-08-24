package org.apollo.game.sync.task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apollo.game.message.impl.PlayerSynchronizationMessage;
import org.apollo.game.model.Position;
import org.apollo.game.model.entity.MobRepository;
import org.apollo.game.model.entity.Player;
import org.apollo.game.sync.block.AppearanceBlock;
import org.apollo.game.sync.block.ChatBlock;
import org.apollo.game.sync.block.SynchronizationBlock;
import org.apollo.game.sync.block.SynchronizationBlockSet;
import org.apollo.game.sync.seg.AddPlayerSegment;
import org.apollo.game.sync.seg.MovementSegment;
import org.apollo.game.sync.seg.RemoveMobSegment;
import org.apollo.game.sync.seg.SynchronizationSegment;
import org.apollo.game.sync.seg.TeleportSegment;

/**
 * A {@link SynchronizationTask} which synchronizes the specified {@link Player} .
 *
 * @author Graham
 */
public final class PlayerSynchronizationTask extends SynchronizationTask {

	/**
	 * The maximum number of players to load per cycle. This prevents the update packet from becoming too large (the
	 * client uses a 5000 byte buffer) and also stops old spec PCs from crashing when they login or teleport.
	 */
	private static final int NEW_PLAYERS_PER_CYCLE = 20;

	/**
	 * The player.
	 */
	private final Player player;

	/**
	 * Creates the {@link PlayerSynchronizationTask} for the specified player.
	 *
	 * @param player The player.
	 */
	public PlayerSynchronizationTask(Player player) {
		this.player = player;
	}

	@Override
	public void run() {
		Position lastKnownRegion = player.getLastKnownRegion();
		boolean regionChanged = player.hasRegionChanged();

		SynchronizationSegment segment;
		SynchronizationBlockSet blockSet = player.getBlockSet();
		if (blockSet.contains(ChatBlock.class)) {
			blockSet = blockSet.clone();
			blockSet.remove(ChatBlock.class);
		}

		if (player.isTeleporting() || player.hasRegionChanged()) {
			segment = new TeleportSegment(blockSet, player.getPosition());
		} else {
			segment = new MovementSegment(blockSet, player.getDirections());
		}

		List<Player> localPlayers = player.getLocalPlayerList();
		int oldLocalPlayers = localPlayers.size();
		List<SynchronizationSegment> segments = new ArrayList<>();

		for (Iterator<Player> it = localPlayers.iterator(); it.hasNext(); ) {
			Player other = it.next();

			if (removePlayer(other)) {
				it.remove();
				segments.add(new RemoveMobSegment());
			} else {
				segments.add(new MovementSegment(other.getBlockSet(), other.getDirections()));
			}
		}

		int added = 0;

		MobRepository<Player> repository = player.getWorld().getPlayerRepository();
		for (Player other : repository) {
			if (localPlayers.size() >= 255) {
				player.flagExcessivePlayers();
				break;
			} else if (added >= NEW_PLAYERS_PER_CYCLE) {
				break;
			}

			if (other != player && other.getPosition().isWithinDistance(player.getPosition(), player.getViewingDistance()) && !localPlayers.contains(other)) {
				localPlayers.add(other);
				added++;

				blockSet = other.getBlockSet();
				if (!blockSet.contains(AppearanceBlock.class)) {
					// TODO check if client has cached appearance
					blockSet = blockSet.clone();
					blockSet.add(SynchronizationBlock.createAppearanceBlock(other));
				}

				segments.add(new AddPlayerSegment(blockSet, other.getIndex(), other.getPosition()));
			}
		}

		PlayerSynchronizationMessage message = new PlayerSynchronizationMessage(lastKnownRegion, player.getPosition(),
				regionChanged, segment, oldLocalPlayers, segments);
		player.send(message);
	}

	/**
	 * Returns whether or not the specified {@link Player} should be removed.
	 *
	 * @param other The Player being tested.
	 * @return {@code true} iff the specified Player should be removed.
	 */
	private boolean removePlayer(Player other) {
		if (other.isTeleporting() || !other.isActive()) {
			return true;
		}

		Position position = player.getPosition();
		Position otherPosition = other.getPosition();
		int distance = player.getViewingDistance();

		return otherPosition.getLongestDelta(position) > distance || !otherPosition.isWithinDistance(position, distance);
	}

}