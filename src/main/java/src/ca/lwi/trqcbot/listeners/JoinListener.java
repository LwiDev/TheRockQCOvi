package src.ca.lwi.trqcbot.listeners;

import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import src.ca.lwi.trqcbot.Main;

public class JoinListener extends ListenerAdapter {

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent e) {
        Main.getWelcomeMessageHandler().createMessage(e.getGuild(), e.getMember());
    }
}
