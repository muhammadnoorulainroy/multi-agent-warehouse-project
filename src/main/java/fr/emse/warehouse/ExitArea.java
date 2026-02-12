package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Exit area (zone Zy) where pallets are delivered. Tracks delivery statistics.
 */
public class ExitArea {

    private final String id;
    private final int[] position;

    private final List<Pallet> deliveredPallets;
    private int totalDeliveryTime;

    public ExitArea(String id, int[] position) {
        this.id = id;
        this.position = position.clone();
        this.deliveredPallets = new ArrayList<>();
        this.totalDeliveryTime = 0;
    }

    public int receivePallet(Pallet pallet, int currentTick) {
        pallet.markDelivered(currentTick);
        int deliveryTime = pallet.getDeliveryTime();
        totalDeliveryTime += deliveryTime;
        deliveredPallets.add(pallet);
        return deliveryTime;
    }

    public boolean isAtExit(int x, int y) {
        return position[0] == x && position[1] == y;
    }

    public boolean isAtExit(int[] pos) {
        return isAtExit(pos[0], pos[1]);
    }

    public String getId() {
        return id;
    }

    public int[] getPosition() {
        return position.clone();
    }

    public int getX() {
        return position[0];
    }

    public int getY() {
        return position[1];
    }

    public int getDeliveredCount() {
        return deliveredPallets.size();
    }

    public int getTotalDeliveryTime() {
        return totalDeliveryTime;
    }

    public double getAverageDeliveryTime() {
        if (deliveredPallets.isEmpty()) {
            return 0;
        }
        return (double) totalDeliveryTime / deliveredPallets.size();
    }

    public List<Pallet> getDeliveredPallets() {
        return new ArrayList<>(deliveredPallets);
    }

    @Override
    public String toString() {
        return String.format("ExitArea[%s at (%d,%d), delivered=%d pallets, avgTime=%.2f]",
                id, position[0], position[1], deliveredPallets.size(), getAverageDeliveryTime());
    }
}
