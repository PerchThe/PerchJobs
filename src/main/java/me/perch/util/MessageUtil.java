package me.perch.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageUtil {

    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacyAmp;
    private final LegacyComponentSerializer legacySection;
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public MessageUtil() {
        this.miniMessage = MiniMessage.miniMessage();
        this.legacyAmp = LegacyComponentSerializer.legacyAmpersand();
        this.legacySection = LegacyComponentSerializer.legacySection();
    }

    public Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();

        if (input.contains("&") || input.contains("§")) {
            return legacyAmp.deserialize(transformHex(input));
        }

        return miniMessage.deserialize(input);
    }

    private String transformHex(String input) {
        Matcher matcher = hexPattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char c : hex.toCharArray()) {
                replacement.append('&').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) return;
        sender.sendMessage(parse(message));
    }
}