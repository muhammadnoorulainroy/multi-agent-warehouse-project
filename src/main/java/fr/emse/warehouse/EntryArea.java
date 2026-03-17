package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EntryArea {

    public enum ArrivalDistribution {
        BINOMIAL,   // P(arrival) = p each tick
        POISSON,    // Poisson process, lambda = arrivalProbability
        UNIFORM,    // One pallet every ceil(1/p) ticks
        GEOMETRIC   // Geometric distribution, resets counter on arrival
    }

    private final String id;
    private final int[] position;
    private final List<Pallet> palletQueue;
    private final Random random;

    private final double arrivalProbability;
    private final ArrivalDistribution distribution;
    private final String[] possibleDestinations;

    private final int uniformInterval;
    private int ticksSinceLastArrival;

    private int totalPalletsGenerated;

    public EntryArea(String id, int[] position, double arrivalProbability,
                     String[] possibleDestinations, long seed,
                     ArrivalDistribution distribution) {
        this.id = id;
        this.position = position.clone();
        this.palletQueue = new ArrayList<>();
        this.arrivalProbability = arrivalProbability;
        this.possibleDestinations = possibleDestinations.clone();
        this.random = new Random(seed);
        this.distribution = distribution;
        this.totalPalletsGenerated = 0;
        this.ticksSinceLastArrival = 0;

        // For uniform: interval = ceil(1/p), e.g. p=0.2 -> every 5 ticks
        this.uniformInterval = (arrivalProbability > 0)
            ? Math.max(1, (int) Math.ceil(1.0 / arrivalProbability))
            : Integer.MAX_VALUE;
    }

    public EntryArea(String id, int[] position, double arrivalProbability,
                     String[] possibleDestinations, long seed) {
        this(id, position, arrivalProbability, possibleDestinations, seed,
             ArrivalDistribution.BINOMIAL);
    }

    public Pallet tick(int currentTick) {
        ticksSinceLastArrival++;

        boolean arrives = false;

        switch (distribution) {
            case BINOMIAL:
                arrives = random.nextDouble() < arrivalProbability;
                break;

            case POISSON:
                // P(at least 1) = 1 - e^(-lambda); cap at 1 pallet per tick
                double lambda = arrivalProbability;
                arrives = random.nextDouble() < (1.0 - Math.exp(-lambda));
                break;

            case UNIFORM:
                arrives = (ticksSinceLastArrival >= uniformInterval);
                break;

            case GEOMETRIC:
                arrives = random.nextDouble() < arrivalProbability;
                break;
        }

        if (arrives) {
            ticksSinceLastArrival = 0;
            return generatePallet(currentTick);
        }
        return null;
    }

    private Pallet generatePallet(int arrivalTick) {
        String destination = possibleDestinations[random.nextInt(possibleDestinations.length)];
        Pallet pallet = new Pallet(arrivalTick, destination, position);
        palletQueue.add(pallet);
        totalPalletsGenerated++;
        return pallet;
    }

    public void addPallet(Pallet pallet) {
        palletQueue.add(pallet);
        totalPalletsGenerated++;
    }

    public Pallet pickupPallet() {
        if (palletQueue.isEmpty()) {
            return null;
        }
        return palletQueue.remove(0);
    }

    public Pallet peekPallet() {
        if (palletQueue.isEmpty()) {
            return null;
        }
        return palletQueue.get(0);
    }

    public boolean hasPallets() {
        return !palletQueue.isEmpty();
    }

    public int getQueueSize() {
        return palletQueue.size();
    }

    public List<Pallet> getWaitingPallets() {
        return new ArrayList<>(palletQueue);
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

    public int getTotalPalletsGenerated() {
        return totalPalletsGenerated;
    }

    public ArrivalDistribution getDistribution() {
        return distribution;
    }

    @Override
    public String toString() {
        return String.format("EntryArea[%s at (%d,%d), dist=%s, queue=%d pallets]",
                id, position[0], position[1], distribution, palletQueue.size());
    }
}
