package dev.airac.ui;

import dev.airac.data.ChartData.Route;
import java.util.*;

final class RouteRows {
    enum Kind { FIX, GAP, END, TC, TD }
    record Row(Kind kind, int index) {}
    static List<Row> of(Route route) {return of(route,null);}
    static List<Row> of(Route route,dev.airac.nav.PerformanceProfile profile) {
        List<Row> rows = new ArrayList<>();
        if (route == null) return rows;
        route.normalizeVias();
        for (int i = 0; i < route.pointIds.size(); i++) {
            if (route.disconnectedBefore(i)) rows.add(new Row(Kind.GAP, i));
            if(profile!=null)for(var marker:profile.markers)if(marker.beforeIndex()==i)
                rows.add(new Row(marker.label().equals("T.C")?Kind.TC:Kind.TD,i));
            rows.add(new Row(Kind.FIX, i));
        }
        if (!route.pointIds.isEmpty()) rows.add(new Row(Kind.END, -1));
        return rows;
    }
    static void controls(net.minecraft.client.gui.DrawContext c, int left, int y, int right, boolean editing) {
        controls(c,left,y,right,editing,"---F-PLN DISCONTINUITY---");
    }
    static void controls(net.minecraft.client.gui.DrawContext c, int left, int y, int right, boolean editing,String label) {
        c.fill(left, y - 2, left + 16, y + 13, editing ? 0xff285054 : Ui.PANEL);
        Ui.text(c, "+", left + 5, y + 2, Ui.CYAN);
        Ui.scaledFit(c, label, left + 19, y + 3, right - left - 38, 0.75f, Ui.AMBER);
        c.fill(right - 16, y - 2, right, y + 13, Ui.PANEL);
        Ui.text(c, "X", right - 12, y + 2, Ui.AMBER);
    }
    private RouteRows() {}
}
