/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client;

import org.gms.dao.entity.MonsterbookDO;
import org.gms.manager.ServerManager;
import org.gms.service.MonsterBookService;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class MonsterBook {
    private int specialCard = 0;
    private int normalCard = 0;
    private int bookLevel = 1;
    private final Map<Integer, Integer> cards = new LinkedHashMap<>();
    private final Lock lock = new ReentrantLock();
    private static final MonsterBookService monsterBookService = ServerManager.getApplicationContext().getBean(MonsterBookService.class);

    public MonsterBook(int cid) {
        loadCards(cid);
    }

    public Set<Entry<Integer, Integer>> getCardSet() {
        lock.lock();
        try {
            return new HashSet<>(cards.entrySet());
        } finally {
            lock.unlock();
        }
    }

    public void addCard(final Client c, final int cardid) {
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.showForeignCardEffect(c.getPlayer().getId()), false);

        Integer qty;
        lock.lock();
        try {
            qty = cards.get(cardid);

            if (qty != null) {
                if (qty < 5) {
                    cards.put(cardid, qty + 1);
                }
            } else {
                cards.put(cardid, 1);
                qty = 0;

                if (cardid / 1000 >= 2388) {
                    specialCard++;
                } else {
                    normalCard++;
                }
            }
        } finally {
            lock.unlock();
        }

        if (qty < 5) {
            if (qty == 0) {     // leveling system only accounts unique cards
                calculateLevel();
            }

            c.sendPacket(PacketCreator.addCard(false, cardid, qty + 1));
            c.sendPacket(PacketCreator.showGainCard());
        } else {
            c.sendPacket(PacketCreator.addCard(true, cardid, 5));
        }
    }

    private void calculateLevel() {
        lock.lock();
        try {
            int collectionExp = (normalCard + specialCard);

            int level = 0, expToNextlevel = 1;
            do {
                level++;
                expToNextlevel += level * 10;
            } while (collectionExp >= expToNextlevel);

            bookLevel = level;  // thanks IxianMace for noticing book level differing between book UI and character info UI
        } finally {
            lock.unlock();
        }
    }

    public int getBookLevel() {
        lock.lock();
        try {
            return bookLevel;
        } finally {
            lock.unlock();
        }
    }

    public Map<Integer, Integer> getCards() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(cards);
        } finally {
            lock.unlock();
        }
    }

    public int getTotalCards() {
        lock.lock();
        try {
            return specialCard + normalCard;
        } finally {
            lock.unlock();
        }
    }

    public int getNormalCard() {
        lock.lock();
        try {
            return normalCard;
        } finally {
            lock.unlock();
        }
    }

    public int getSpecialCard() {
        lock.lock();
        try {
            return specialCard;
        } finally {
            lock.unlock();
        }
    }

    public void loadCards(final int chrId) {
        lock.lock();

        try {
            List<MonsterbookDO> monsterbookDOList = monsterBookService.getByCharacterId(chrId);
            monsterbookDOList.forEach(monsterbookDO -> {
                if (monsterbookDO.getCardid() / 1000 >= 2388) {
                    specialCard++;
                } else {
                    normalCard++;
                }
                cards.put(monsterbookDO.getCardid(), monsterbookDO.getLevel());
            });
        } finally {
            lock.unlock();
        }

        calculateLevel();
    }

    public void saveCards(Connection con, int chrId) throws SQLException {
        final String query = """
                INSERT INTO monsterbook (charid, cardid, level)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE level = ?;
                """;
        try (final PreparedStatement ps = con.prepareStatement(query)) {
            for (Map.Entry<Integer, Integer> cardAndLevel : cards.entrySet()) {
                final int card = cardAndLevel.getKey();
                final int level = cardAndLevel.getValue();
                // insert
                ps.setInt(1, chrId);
                ps.setInt(2, card);
                ps.setInt(3, level);

                // update
                ps.setInt(4, level);

                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static int[] getCardTierSize() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM monstercarddata GROUP BY floor(cardid / 1000);", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = ps.executeQuery()) {
            rs.last();
            int[] tierSizes = new int[rs.getRow()];
            rs.beforeFirst();

            while (rs.next()) {
                tierSizes[rs.getRow() - 1] = rs.getInt(1);
            }

            return tierSizes;
        } catch (SQLException e) {
            e.printStackTrace();
            return new int[0];
        }
    }
}
