package fr.emse.warehouse;

public class Pallet {

    private static int NEXT_ID = 1;

    private final int id;
    private final int arrivalTick;
    private final String destination;  // Exit area ID (e.g., "Z1", "Z2")
    private int[] position;
    private boolean delivered;
    private int deliveryTick;

    public Pallet(int arrivalTick, String destination, int[] position) {
        this.id = NEXT_ID++;
        this.arrivalTick = arrivalTick;
        this.destination = destination;
        this.position = position.clone();
        this.delivered = false;
        this.deliveryTick = -1;
    }

    public int getId() {
        return id;
    }

    public int getArrivalTick() {
        return arrivalTick;
    }

    public String getDestination() {
        return destination;
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

    public boolean isDelivered() {
        return delivered;
    }

    public int getDeliveryTick() {
        return deliveryTick;
    }

    public int getDeliveryTime() {
        if (!delivered) {
            return -1;
        }
        return deliveryTick - arrivalTick;
    }

    public int getWaitingTime(int currentTick) {
        if (delivered) {
            return deliveryTick - arrivalTick;
        }
        return currentTick - arrivalTick;
    }

    public void setPosition(int[] newPosition) {
        this.position = newPosition.clone();
    }

    public void setPosition(int x, int y) {
        this.position[0] = x;
        this.position[1] = y;
    }

    public void markDelivered(int deliveryTick) {
        this.delivered = true;
        this.deliveryTick = deliveryTick;
    }

    public static void resetIdCounter() {
        NEXT_ID = 1;
    }

    @Override
    public String toString() {
        return String.format("Pallet[id=%d, dest=%s, arrived=%d, pos=(%d,%d), delivered=%b]",
                id, destination, arrivalTick, position[0], position[1], delivered);
    }
}
