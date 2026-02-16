package com.jyoma.team;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.scoreboard.Scoreboard;

import net.md_5.bungee.api.chat.*;
import net.md_5.bungee.api.ChatColor;
import org.jetbrains.annotations.NotNull;

import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.*;

public class Team extends JavaPlugin implements Listener, TabCompleter {
    private Scoreboard scoreboard;
    private final Map<UUID, String> roleMap = new HashMap<>();
    private final Map<UUID, Boolean> teamChatMode = new HashMap<>();
    private final Map<String, Boolean> teamPvp = new HashMap<>();
    private final Map<UUID, String> inviteMap = new HashMap<>();

    @Override
    public void onEnable() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("팀")).setExecutor(this);
        Objects.requireNonNull(getCommand("팀")).setTabCompleter(this);
    }

    /* ===============================
                명령어 처리
       =============================== */

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (args.length == 0) return true;

        org.bukkit.scoreboard.Team team = getPlayerTeam(p);

        switch (args[0]) {

            case "채팅":
                toggleTeamChat(p);
                break;

            case "초대":
                if (!canInvite(p)) return true;
                if (args.length < 2) return true;
                invitePlayer(p, args[1]);
                break;

            case "탈퇴":
                if (team == null) return true;
                leaveTeam(p);
                break;

            case "추방":
                if (!canKick(p)) return true;
                if (args.length < 2) return true;
                kickPlayer(p, args[1]);
                break;

            case "pvp":
                if (!canInvite(p)) return true;
                togglePvp(p);
                break;

            case "해산":
                if (!isLeader(p)) return true;
                disbandTeam(p);
                break;
        }

        return true;
    }

    /* ===============================
                팀 채팅
       =============================== */

    private void toggleTeamChat(Player p) {
        boolean mode = teamChatMode.getOrDefault(p.getUniqueId(), false);
        teamChatMode.put(p.getUniqueId(), !mode);
        p.sendMessage(!mode ? "팀 채팅 ON" : "팀 채팅 OFF");
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();

        if (!teamChatMode.getOrDefault(p.getUniqueId(), false)) return;

        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        if (team == null) return;

        e.setCancelled(true);

        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage("§a[팀] §f" + p.getName() + ": " + e.message());
        }
    }

    /* ===============================
                초대 시스템
       =============================== */

    private void invitePlayer(Player inviter, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) return;

        org.bukkit.scoreboard.Team team = getPlayerTeam(inviter);
        inviteMap.put(target.getUniqueId(), team.getName());

        inviter.sendMessage(name + "님에게 팀 가입 초대를 보냈습니다.");
        target.sendMessage(inviter.getName() + "님이 팀 가입 초대를 보냈습니다.");

        TextComponent accept = new TextComponent("[수락]");
        accept.setColor(ChatColor.GREEN);
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/팀수락"));

        TextComponent deny = new TextComponent("[거절]");
        deny.setColor(ChatColor.RED);
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/팀거절"));

        target.spigot().sendMessage(accept, new TextComponent(" "), deny);
    }

    /* ===============================
                팀 탈퇴
       =============================== */

    private void leaveTeam(Player p) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        team.removeEntry(p.getName());
        broadcast(team, p.getName() + "님이 팀을 탈퇴하였습니다.");
    }

    /* ===============================
                추방
       =============================== */

    private void kickPlayer(Player executor, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) return;

        org.bukkit.scoreboard.Team team = getPlayerTeam(executor);
        team.removeEntry(name);

        broadcast(team, executor.getName() + "님에 의해 " + name + "님이 팀에서 추방되었습니다.");
        target.sendMessage("팀에서 추방되었습니다.");
    }

    /* ===============================
                PVP
       =============================== */

    private void togglePvp(Player p) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        boolean mode = teamPvp.getOrDefault(team.getName(), false);
        teamPvp.put(team.getName(), !mode);
        broadcast(team, !mode ? "팀 pvp ON" : "팀 pvp OFF");
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;

        Player a = (Player) e.getDamager();
        Player b = (Player) e.getEntity();

        org.bukkit.scoreboard.Team ta = getPlayerTeam(a);
        org.bukkit.scoreboard.Team tb = getPlayerTeam(b);

        if (ta == null || tb == null) return;
        if (!ta.getName().equals(tb.getName())) return;

        if (!teamPvp.getOrDefault(ta.getName(), false))
            e.setCancelled(true);
    }

    /* ===============================
                해산
       =============================== */

    private void disbandTeam(Player leader) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(leader);

        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage("팀이 해산되었습니다.");
        }

        team.unregister();
    }

    /* ===============================
                권한 체크
       =============================== */

    private boolean canInvite(Player p) {
        String role = roleMap.get(p.getUniqueId());
        return role != null && (role.equals("LEADER") || role.equals("SUB"));
    }

    private boolean canKick(Player p) {
        return canInvite(p);
    }

    private boolean isLeader(Player p) {
        return "LEADER".equals(roleMap.get(p.getUniqueId()));
    }

    private org.bukkit.scoreboard.Team getPlayerTeam(Player p) {
        return scoreboard.getEntryTeam(p.getName());
    }

    private void broadcast(org.bukkit.scoreboard.Team team, String msg) {
        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage("§a[팀] §f" + msg);
        }
    }

    /* ===============================
                자동완성
       =============================== */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length == 1)
            return Arrays.asList("채팅", "초대", "탈퇴", "추방", "pvp", "해산");

        if (args.length == 2 && (args[0].equals("초대") || args[0].equals("추방"))) {
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                list.add(p.getName());
            return list;
        }

        return Collections.emptyList();
    }
}