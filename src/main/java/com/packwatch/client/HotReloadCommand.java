package com.packwatch.client;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * {@code /pw ctm} - toggle CTM tile numbers on the block atlas. Registered under the full name {@code packwatch},
 * with {@code pw} as a short alias -- both work identically.
 */
public class HotReloadCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "packwatch";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("pw");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/pw ctm [f3]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "ctm");
        if (args.length == 2 && args[0].equals("ctm")) return getListOfStringsMatchingLastWord(args, "f3");
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equals("ctm")) {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            return;
        }

        if (args.length >= 2 && args[1].equals("f3")) {
            sender.addChatMessage(new ChatComponentText(CtmNumberOverlay.toggleFollowDebugScreen()));
            return;
        }

        String message = CtmNumberOverlay.toggle();
        if (message == null) message = "CTM tile numbers on.";
        sender.addChatMessage(new ChatComponentText(message));
    }
}
