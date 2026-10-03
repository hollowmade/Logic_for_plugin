package ru.logic.tierplugin.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.Tier;

import java.util.Locale;

/**
 * Chat styling for every player-facing message: MiniMessage templates, the plugin
 * prefix, tier colours and the fight-metrics line with a hover breakdown.
 * <p>
 * Player names and other user input always go through {@link #plain} so they can
 * never inject formatting tags.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Locale RU = Locale.forLanguageTag("ru");

    public static final String ACCENT = "#FFB347";
    public static final String PREFIX = "<gradient:#FFB347:#FF5E3A><bold>ТИРЫ</bold></gradient> <dark_gray>»</dark_gray> ";
    public static final String RULE = "<dark_gray><strikethrough>                                                       </strikethrough></dark_gray>";
    public static final String DOT = " <dark_gray>·</dark_gray> ";
    public static final String ARROW = " <dark_gray>→</dark_gray> ";

    private Text() {}

    // ── Building and sending ───────────────────────────────────

    public static Component parse(String template, TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }

    /** Message with the plugin prefix. */
    public static void send(CommandSender to, String template, TagResolver... resolvers) {
        to.sendMessage(parse(PREFIX + template, resolvers));
    }

    /** Error message with the plugin prefix. */
    public static void error(CommandSender to, String template, TagResolver... resolvers) {
        to.sendMessage(parse(PREFIX + "<#FF6B6B>" + template, resolvers));
    }

    /** Line without the prefix, for multi-line blocks. */
    public static void line(CommandSender to, String template, TagResolver... resolvers) {
        to.sendMessage(parse(template, resolvers));
    }

    /** Placeholder with literal text (player names, user input): tags inside are not parsed. */
    public static TagResolver plain(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Placeholder with a MiniMessage snippet built by this plugin. */
    public static TagResolver styled(String key, String template) {
        return Placeholder.parsed(key, template);
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** {@code <arg>} rendered literally inside a template (MiniMessage would parse it as a tag). */
    public static String arg(String name) {
        return "\\<" + name + ">";
    }

    // ── Domain formatting ─────────────────────────────────────

    public static String tier(Tier tier) {
        if (tier == null) return "<gray>Без тира</gray>";
        String color = switch (tier) {
            case LT5 -> "#A0A0A0";
            case HT5 -> "#D6D6D6";
            case LT4 -> "#7BE07B";
            case HT4 -> "#2EBD2E";
            case LT3 -> "#6FDBF7";
            case HT3 -> "#1E9BE0";
            case LT2 -> "#C98BFF";
            case HT2 -> "#9B4DFF";
            case LT1 -> "#FFD24D";
            case HT1 -> "#FF8F1A";
        };
        return "<" + color + "><bold>" + tier.name() + "</bold></" + color + ">";
    }

    /** Tier with the provisional mark, e.g. "HT5 (временный)". */
    public static String tier(Tier tier, boolean provisional) {
        return tier(tier) + (provisional ? " <gray>(временный)</gray>" : "");
    }

    public static String mode(Gamemode gamemode) {
        return switch (gamemode.name()) {
            case "SWORD" -> "Меч";
            case "CRYSTAL" -> "Кристаллы";
            case "CART" -> "Вагонетки";
            default -> gamemode.name();
        };
    }

    public static String num(double value, int decimals) {
        return String.format(RU, "%." + decimals + "f", value);
    }

    /** Signed, coloured change: "<green>+21</green>" or "<red>−21</red>". */
    public static String delta(double value) {
        long v = Math.round(value);
        if (v > 0) return "<green>+" + v + "</green>";
        if (v < 0) return "<red>−" + (-v) + "</red>";
        return "<gray>±0</gray>";
    }

    public static String percent(double fraction) {
        return num(fraction * 100, 0) + "%";
    }

    /** One-line metrics summary; hover shows the full breakdown. */
    public static Component metrics(FightMetrics m) {
        String acc = m.accuracy() < 0 ? "—" : num(m.accuracy(), 0) + "%";
        Component line = parse("<gray>Точность</gray> <white>" + acc + "</white> <dark_gray>(" + m.hits() + "/" + m.swings() + ")</dark_gray>"
                + DOT + "<gray>Урон</gray> <white>" + num(m.damageDealt(), 1) + "</white><dark_gray>/</dark_gray><white>" + num(m.damageTaken(), 1) + "</white>"
                + DOT + "<gray>КПС</gray> <white>" + num(m.avgCps(), 1) + "</white>"
                + DOT + "<gray>Комбо</gray> <white>" + m.maxCombo() + "</white> <dark_gray>[?]</dark_gray>");
        Component hover = parse("<" + ACCENT + "><bold>Статистика боя</bold></" + ACCENT + ">"
                + "\n<gray>Попадания:</gray> <white>" + m.hits() + "</white> <gray>из</gray> <white>" + m.swings() + "</white> <gray>атак</gray>"
                + "\n<gray>Урон нанесён:</gray> <white>" + num(m.damageDealt(), 1) + "</white> <gray>получен:</gray> <white>" + num(m.damageTaken(), 1) + "</white>"
                + "\n<gray>КПС средний:</gray> <white>" + num(m.avgCps(), 1) + "</white> <gray>пик:</gray> <white>" + m.maxCps() + "</white>"
                + "\n<gray>Комбо лучшее:</gray> <white>" + m.maxCombo() + "</white> <gray>среднее:</gray> <white>" + num(m.avgCombo(), 1)
                + "</white> <gray>медиана:</gray> <white>" + num(m.medianCombo(), 1) + "</white> <dark_gray>(серий: " + m.combos() + ")</dark_gray>");
        return line.hoverEvent(HoverEvent.showText(hover));
    }
}
