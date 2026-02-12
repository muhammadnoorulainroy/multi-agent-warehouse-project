package fr.emse;

import java.util.List;

import fr.emse.fayol.maqit.simulator.ColorSimFactory;
import fr.emse.fayol.maqit.simulator.components.ColorObstacle;
import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.components.ComponentType;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.SituatedComponent;
import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

public class BasicSimulator extends ColorSimFactory {

    public BasicSimulator(SimProperties sp) {
        super(sp);
    }

    @Override
    public void createEnvironment() {
        this.environment = new ColorGridEnvironment(this.sp.seed);
    }

    @Override
    public void createObstacle() {
        for (int i = 0; i < this.sp.nbobstacle; i++) {
            int[] pos = this.environment.getPlace();
            int[] rgb = {
                this.sp.colorobstacle.getRed(),
                this.sp.colorobstacle.getGreen(),
                this.sp.colorobstacle.getBlue()
            };
            ColorObstacle obstacle = new ColorObstacle(pos, rgb);
            addNewComponent(obstacle);
        }
    }

    @Override
    public void createRobot() {
        for (int i = 0; i < this.sp.nbrobot; i++) {
            int[] pos = this.environment.getPlace();
            BasicRobot robot = new BasicRobot(
                    "Robot" + i,
                    this.sp.field,
                    this.sp.debug,
                    pos,
                    this.sp.colorrobot,
                    this.sp.rows,
                    this.sp.columns
            );
            addNewComponent(robot);
        }
    }

    @Override
    public void createGoal() {
        // No goals needed for now
    }

    private void printGrid() {
        ColorSimpleCell[][] grid = (ColorSimpleCell[][]) this.environment.getGrid();
        System.out.print("   ");
        for (int c = 0; c < this.sp.columns; c++) {
            System.out.printf("%3d", c);
        }
        System.out.println();

        for (int r = 0; r < this.sp.rows; r++) {
            System.out.printf("%2d ", r);
            for (int c = 0; c < this.sp.columns; c++) {
                SituatedComponent content = grid[r][c].getContent();
                if (content == null) {
                    System.out.print("  .");
                } else if (content.getComponentType() == ComponentType.robot) {
                    System.out.print("  R");
                } else if (content.getComponentType() == ComponentType.obstacle) {
                    System.out.print("  X");
                } else {
                    System.out.print("  ?");
                }
            }
            System.out.println();
        }
        System.out.println();
    }

    @Override
    public void schedule() {
        List<ColorRobot<ColorSimpleCell>> lr = this.environment.getRobot();
        for (int i = 0; i < this.sp.step; i++) {
            if (this.sp.debug == 1) {
                System.out.println("Step: " + i);
            }

            // 1. Move all robots
            for (ColorRobot<ColorSimpleCell> r : lr) {
                int[] pos = r.getLocation();
                ColorSimpleCell[][] per = this.environment.getNeighbor(r.getX(), r.getY(), r.getField());
                r.updatePerception(per);
                r.move(1);
                updateEnvironment(pos, r.getLocation(), r.getId());
            }

            // 2. Distribute messages between robots
            for (ColorRobot<ColorSimpleCell> r : lr) {
                for (ColorRobot<ColorSimpleCell> rr : lr) {
                    for (Message m : ((BasicRobot) rr).popSentMessages()) {
                        if (r.getId() != rr.getId()) {
                            ((BasicRobot) r).receiveMessage(m);
                        }
                    }
                }
            }

            // 3. Each robot reads and handles its received messages
            for (ColorRobot<ColorSimpleCell> r : lr) {
                ((BasicRobot) r).readMessages();
            }

            // 4. Print grid and refresh display
            if (this.sp.debug == 1) {
                printGrid();
            }
            refreshGW();

            try {
                Thread.sleep(this.sp.waittime);
            } catch (InterruptedException ex) {
                System.out.println(ex);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        IniFile ifile = new IniFile("configuration.ini");
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();

        System.out.printf("Config: robots=%d obstacles=%d grid=%dx%d steps=%d seed=%d field=%d debug=%d%n",
                sp.nbrobot, sp.nbobstacle, sp.rows, sp.columns, sp.step, sp.seed, sp.field, sp.debug);

        BasicSimulator simulator = new BasicSimulator(sp);
        simulator.createEnvironment();
        simulator.createObstacle();
        simulator.createRobot();
        simulator.initializeGW();
        simulator.refreshGW();
        simulator.schedule();
    }
}
