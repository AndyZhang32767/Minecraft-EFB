package dev.airac.ui;

import net.minecraft.text.Text;
import java.util.Map;

/** MCDU terminology remains in English regardless of the selected game language. */
public final class McduText {
    private McduText() {}
    public static Text t(String key) { return Text.literal(tr(key)); }
    public static String tr(String key) {
        return switch (key.replace("ui.airac.", "")) {
            case "airways_mode" -> "AIRWAYS";
            case "route_mode" -> "ROUTE";
            case "init" -> "INIT DEP/ARR";
            case "departure_setup" -> "DEP RWY / SID";
            case "arrival_setup" -> "ARR RWY / STAR";
            case "select_sid" -> "SELECT SID";
            case "select_star" -> "SELECT STAR";
            case "departure" -> "DEP";
            case "arrival" -> "ARR";
            case "entry" -> "FROM";
            case "airway_name" -> "VIA";
            case "exit" -> "TO";
            case "name" -> "F-PLN NAME";
            case "none" -> "NONE";
            case "remove" -> "CLR WPT";
            case "remove_airway" -> "CLR AIRWAY";
            case "new_plan" -> "NEW F-PLN";
            case "rename" -> "RENAME F-PLN";
            case "delete_plan" -> "DELETE F-PLN";
            case "restart" -> "RESTART";
            case "skip" -> "NEXT WPT";
            case "reset_plan" -> "CLR F-PLN";
            case "clear_routes" -> "CLR ALL F-PLN";
            case "previous" -> "<";
            case "next" -> ">";
            case "save" -> "INSERT*";
            case "cancel" -> "<RETURN";
            case "confirm" -> "CONFIRM";
            case "confirm_action" -> "CONFIRM*";
            case "confirm_reset_plan" -> "CLEAR F-PLN?";
            case "confirm_delete_plan" -> "DELETE F-PLN?";
            case "confirm_clear_routes" -> "DELETE ALL F-PLN?";
            case "no_plan" -> "NO F-PLN";
            case "editing_gap" -> "INSERT AT DISCONTINUITY";
            case "appending", "append_end" -> "END OF F-PLN";
            case "set_dep_arr" -> "ENTER DEP/ARR";
            case "sync_conflict" -> "NAV DATA CHANGED - RESELECT";
            case "sync_read_only" -> "NOT ALLOWED";
            case "invalid_airport", "invalid_endpoints" -> "NOT IN DATABASE";
            case "airway_not_connected" -> "AWY/WPT MISMATCH";
            case "invalid_runway_selection", "invalid_procedure" -> "RWY/PROC MISMATCH";
            case "invalid_gap" -> "NOT ALLOWED";
            default -> "FORMAT ERROR";
        };
    }
    public static String required(Map<String,String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ENTER " + tr(key));
        return value;
    }
}
