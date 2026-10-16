package dev.airac.nav;

import dev.airac.data.ChartData;
import dev.airac.data.ChartData.Airway;

/** Edits public airway topology without modifying the underlying fixes or private plans. */
public final class AirwayEditor {
    private AirwayEditor() {}
    public static void remove(Airway airway,int index) {
        if(index<0 || index>=airway.pointIds.size() || airway.pointIds.size()<2)return;
        airway.normalizeGaps();airway.pointIds.remove(index);airway.discontinuities.remove(index);
        if(index<airway.pointIds.size())airway.discontinuities.set(index,index>0);
    }
    /** Cursor -1 appends, >=0 inserts; -2 pauses after joining the downstream fix. */
    public static int insert(ChartData chart,Airway airway,int cursor,String id) {
        if(cursor < -1 || chart.point(id)==null)throw new IllegalArgumentException("invalid_endpoints");
        airway.normalizeGaps();int index=cursor<0?airway.pointIds.size():Math.clamp(cursor,0,airway.pointIds.size());
        if(index>0 && airway.pointIds.get(index-1).equals(id))return cursor;
        if(index<airway.pointIds.size() && airway.pointIds.get(index).equals(id)) {
            airway.discontinuities.set(index,false);return -2;
        }
        if(airway.pointIds.size()>=2048)throw new IllegalArgumentException("sync_invalid");
        boolean gap=airway.disconnectedBefore(index);
        airway.pointIds.add(index,id);airway.discontinuities.add(index,false);
        if(index+1<airway.pointIds.size())airway.discontinuities.set(index+1,gap);
        return cursor<0?-1:index+1;
    }
    public static void connect(Airway airway,int before) {
        airway.normalizeGaps();if(airway.disconnectedBefore(before))airway.discontinuities.set(before,false);
    }
}
