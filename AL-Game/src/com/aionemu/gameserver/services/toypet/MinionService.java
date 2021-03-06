/**
 * This file is part of Aion-Lightning <aion-lightning.org>.
 *
 *  Aion-Lightning is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Aion-Lightning is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details. *
 *  You should have received a copy of the GNU General Public License
 *  along with Aion-Lightning.
 *  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.services.toypet;

import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.dao.DAOManager;
import com.aionemu.gameserver.controllers.observer.ItemUseObserver;
import com.aionemu.gameserver.dao.PlayerMinionsDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.DescriptionId;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.MinionCommonData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.minion.MinionBuff;
import com.aionemu.gameserver.model.templates.item.ItemUseLimits;
import com.aionemu.gameserver.model.templates.item.actions.AbstractItemAction;
import com.aionemu.gameserver.model.templates.item.actions.ItemActions;
import com.aionemu.gameserver.model.templates.minion.MinionSkill;
import com.aionemu.gameserver.model.templates.minion.MinionTemplate;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ITEM_USAGE_ANIMATION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MINIONS;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUEST_ACTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SYSTEM_MESSAGE;
import com.aionemu.gameserver.questEngine.model.QuestState;
import com.aionemu.gameserver.questEngine.model.QuestStatus;
import com.aionemu.gameserver.restrictions.RestrictionsManager;
import com.aionemu.gameserver.services.SkillLearnService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;

public class MinionService {
	private List<Integer> minions;
	private MinionBuff mb;
	private Logger log = LoggerFactory.getLogger(MinionService.class);

	public void init() {
		this.minions = DataManager.MINION_DATA.getAll();
		this.mb = new MinionBuff();
		log.info("MinionService initialized");
	}
	
	public void onPlayerLogin(Player player) {
		PacketSendUtility.sendPacket(player, new SM_MINIONS(0, player.getMinionList().getMinions()));
		PacketSendUtility.sendPacket(player, new SM_MINIONS(9, 0)); // TODO Read from DB TimeLeft (MinionFunction)
		PacketSendUtility.sendPacket(player, new SM_MINIONS(11, player.getMinionSkillPoints(), false));
		PacketSendUtility.sendPacket(player, new SM_MINIONS(12));
	}

	public void addMinion(final Player player, final int itemObjId) {
		if (player.getMinionList().getMinions().size() == 200) { // TODO MESSAGE ?
			return;
		}
		
		final Item item = player.getInventory().getItemByObjId(itemObjId);
		PacketSendUtility.broadcastPacket(player, new SM_ITEM_USAGE_ANIMATION(player.getObjectId(), 0, itemObjId, item.getItemId(), 1500, 0), true);

		final ItemUseObserver itemUseObserver = new ItemUseObserver() {
			
			@Override
			public void abort() {
				player.getController().cancelTask(TaskId.ITEM_USE);
				player.removeItemCoolDown(item.getItemTemplate().getUseLimits().getDelayId());
				PacketSendUtility.sendPacket(player, SM_SYSTEM_MESSAGE.STR_ITEM_CANCELED(new DescriptionId(item.getItemTemplate().getNameId())));
				PacketSendUtility.broadcastPacket(player, new SM_ITEM_USAGE_ANIMATION(player.getObjectId(), 0, itemObjId, item.getItemId(), 0, 2), true);
				player.getObserveController().removeObserver(this);
			}
		};
		
		player.getObserveController().attach(itemUseObserver);
		player.getController().addTask(TaskId.ITEM_USE, ThreadPoolManager.getInstance().schedule(new Runnable() {
			
			@Override
			public void run() {
				player.getObserveController().removeObserver(itemUseObserver);
				player.getController().cancelTask(TaskId.ITEM_USE);
				PacketSendUtility.broadcastPacket(player, new SM_ITEM_USAGE_ANIMATION(player.getObjectId(), 0, itemObjId, item.getItemId(), 0, 1), true);
			
				int minionId = 0;
				String grade = "";
				int level = 0;
				if (!item.getItemTemplate().getMinionTicket()) {
					return;
				}
				if (item.getItemTemplate().isMinionCashContract()) {
					switch (item.getItemTemplate().getTemplateId()) {
						case 190080028: //Abija's
							minionId = 980043;
							break;
						case 190080029: //Hamerun's
							minionId = 980053;
							break;
						case 190080030: //Kerubiel's
							minionId = 980013;
							break;
						case 190080031: //Seiren's
							minionId = 980023;
							break;
						case 190080032: //Steel Rose's
							minionId = 980033;
							break;
						case 190080033: //Kerubian's
							minionId = 980011;
							break;
						default:
							minionId = minions.get(new Random().nextInt(minions.size()));
							break;
					}
					MinionTemplate minionTemplate = DataManager.MINION_DATA.getMinionTemplate(minionId);
					grade = minionTemplate.getGrade();
					level = minionTemplate.getLevel();
					log.info("Cash-Contract"); // TODO
				} else {
					minionId = minions.get(new Random().nextInt(minions.size()));
					MinionTemplate minionTemplate = DataManager.MINION_DATA.getMinionTemplate(minionId);
					grade = minionTemplate.getGrade();
					level = minionTemplate.getLevel();
					log.info("Normal-Contract"); // TODO
				}
				if (!player.getInventory().decreaseByObjectId(itemObjId, 1)) {
					return;
				}
				MinionCommonData addNewMinion = player.getMinionList().addNewMinion(player, minionId, "Minion", grade, level);
				if (addNewMinion != null) {
					PacketSendUtility.sendPacket(player, new SM_MINIONS(1, addNewMinion));
					player.getMinionList().updateMinionsList();
					checkQuest(player, item);
				}
			}
		}, 1500));
	}
	
	private void checkQuest(Player player, Item item) {
		switch (player.getRace()) {
		case ELYOS:
			if (player.getQuestStateList().hasQuest(15545) && item.getItemId() == 190080010) {
				QuestState qs = player.getQuestStateList().getQuestState(15545);
				if (qs.getStatus() == QuestStatus.START) {
					qs.setQuestVar(1);
					qs.setStatus(QuestStatus.REWARD);
					PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(15545, qs.getStatus(), qs.getQuestVars().getQuestVars()));
					player.getController().updateNearbyQuests();
				}
			}
			break;
		case ASMODIANS:
			if (player.getQuestStateList().hasQuest(25545) && item.getItemId() == 190080011) {
				QuestState qs = player.getQuestStateList().getQuestState(25545);
				if (qs.getStatus() == QuestStatus.START) {
					qs.setQuestVar(1);
					qs.setStatus(QuestStatus.REWARD);
					PacketSendUtility.sendPacket(player, new SM_QUEST_ACTION(25545, qs.getStatus(), qs.getQuestVars().getQuestVars()));
					player.getController().updateNearbyQuests();
				}
			}
			break;
		default:
			break;

		}
	}

	public void spawnMinion(Player player, int minionObjId) {
		MinionCommonData minion = player.getMinionList().getMinion(minionObjId);
		int minionId = minion.getMinionId();
		MinionTemplate minionTemplate = DataManager.MINION_DATA.getMinionTemplate(minionId);
		Iterator<MinionSkill> iterator = minionTemplate.getAction().getSkillsCollections().iterator();
		while (iterator.hasNext()) {
			player.getSkillList().addSkill(player, iterator.next().getSkillId(), 1);
		}
		if (player.isMinionSpawned()) {
			despawnMinion(player, player.getMinionList().getLastUsed());
		}
		player.setMinionSpawned(true);
		player.getMinionList().setLastUsed(minionObjId);
		mb.apply(player, minionId);
		PacketSendUtility.broadcastPacketAndReceive(player,	new SM_MINIONS(5, minion.getName(), minionObjId, minionId, player.getObjectId()));
	}

	public void despawnMinion(Player player, int minionObjId) {
		MinionCommonData minion = player.getMinionList().getMinion(minionObjId);
		int minionId = minion.getMinionId();
		Iterator<MinionSkill> iterator = DataManager.MINION_DATA.getMinionTemplate(minionId).getAction().getSkillsCollections().iterator();
		while (iterator.hasNext()) {
			SkillLearnService.removeSkill(player, iterator.next().getSkillId());
		}
		player.setMinionSpawned(false);
		mb.end(player);
		PacketSendUtility.broadcastPacketAndReceive(player, new SM_MINIONS(6, minionObjId));
	}

	public void growthUpMinion(Player player, int n) { // TODO
		log.info("Called growthUpMinion");
	}

	public void renameMinion(Player player, int minionObjId, String name) {
		MinionCommonData minion = player.getMinionList().getMinion(minionObjId);
		minion.setName(name);
		DAOManager.getDAO(PlayerMinionsDAO.class).updateMinionName(minion);
		PacketSendUtility.broadcastPacketAndReceive(player, new SM_MINIONS(3, minion));
	}
	
	public void addMinionSkillPoints(Player player, boolean charge, boolean autoCharge) {
		final int maxSkillPoints = 50000;
		final int currentSkillPoints = player.getMinionSkillPoints();
		final int skillPointsToAdd = maxSkillPoints - currentSkillPoints;
		final int price = skillPointsToAdd * 20;
		if (player.getInventory().getKinah() < price) {
			return;
		}
		if (charge) {
			player.getInventory().decreaseKinah(price);
			player.setMinionSkillPoints(maxSkillPoints);
			PacketSendUtility.sendPacket(player, new SM_MINIONS(11, maxSkillPoints , autoCharge));
		} else {
			PacketSendUtility.sendPacket(player, new SM_MINIONS(11, currentSkillPoints , autoCharge));
		}
		
	}
	
	public void activateMinionFunction(Player player) {
		long test = 1509901323;// 5.11.2017	
		if (player.getInventory().tryDecreaseKinah(25000000)) {
			PacketSendUtility.sendPacket(player, new SM_MINIONS(9, test));
		} else {
			return;
		}
	}
	
	public void addMinionFunctionItems(Player player, int subSwitch, int value, int value1, int value2, int value3) {
		PacketSendUtility.sendPacket(player, new SM_MINIONS(8, 0, value1, value2, value3));
	}
	
	//TODO
	public void buffPlayer (final Player player, int subSwitch, int value, final int value1, final int value2, final int value3) {
		Item item = player.getInventory().getFirstItemByItemId(value2);
		ItemActions itemActions = item.getItemTemplate().getActions();
		ItemUseLimits limit = new ItemUseLimits();
		int useDelay = player.getItemCooldown(item.getItemTemplate()) / 3;
		if (useDelay < 3000) {
			useDelay = 3000;
		}
		limit.setDelayId(item.getItemTemplate().getUseLimits().getDelayId());
		limit.setDelayTime(useDelay);
		
		if (player.isItemUseDisabled(limit)) {
			//schedule re-check
			ThreadPoolManager.getInstance().schedule(new Runnable() {
				@Override
				public void run() {
						PacketSendUtility.sendPacket(player, new SM_MINIONS(8, 768, value1, value2, value3));
				}
			}, useDelay);
		}
		if (!RestrictionsManager.canUseItem(player, item) || player.isProtectionActive()) {
			//client sends the correct restriction message with that
			player.addItemCoolDown(limit.getDelayId(), System.currentTimeMillis() + useDelay, useDelay / 1000);
		}
		
		player.getController().cancelCurrentSkill();
		
		for (AbstractItemAction itemAction : itemActions.getItemActions()) {
			if (itemAction.canAct(player, item, null)) {
				itemAction.act(player, item, null);
			}
		}
//		PacketSendUtility.sendPacket(player, new SM_MINIONS(8, 768, value1, value2, value3));
	}
	
	public void deaktivateLoot(Player player, int subSwitch, int value, int value1, int value2, int value3) {
		PacketSendUtility.sendPacket(player, new SM_MINIONS(8, 1, value1, value2, value3));
	}

	public static MinionService getInstance() {
		return SingletonHolder.instance;
	}

	private static class SingletonHolder {

		protected static final MinionService instance = new MinionService();
	}
}
