# maqit-simulator-1.0.jar — API Reference

> Decompiled from `lib/maqit-simulator-1.0.jar`. This document covers all public/protected classes and their members.

---

## Table of Contents

- [Configuration](#configuration)
  - [IniFile](#inifile)
  - [SimProperties](#simproperties)
  - [Debug](#debug)
  - [MatrixOp](#matrixop)
  - [Mqtt](#mqtt)
- [Simulator (Factory)](#simulator-factory)
  - [SimFactory](#simfactory)
  - [ColorSimFactory](#colorsimfactory)
- [Environment](#environment)
  - [Cell](#cell)
  - [SimpleCell](#simplecell)
  - [ColorSimpleCell](#colorsimplecell)
  - [ComposedCell](#composedcell)
  - [GridEnvironment](#gridenvironment)
  - [SimpleGridEnvironment](#simplegridenvironment)
  - [ColorGridEnvironment](#colorgridenvironment)
  - [Location](#location)
  - [Goal / ColorGoal](#goal--colorgoal)
- [Components](#components)
  - [SituatedComponent](#situatedcomponent)
  - [ColorSituatedComponent](#colorsituatedcomponent)
  - [Obstacle / ColorObstacle](#obstacle--colorobstacle)
  - [Robot](#robot)
  - [ColorRobot](#colorrobot)
  - [InteractionRobot](#interactionrobot)
  - [ColorInteractionRobot](#colorinteractionrobot)
  - [MobileComponent (interface)](#mobilecomponent-interface)
  - [InteractionComponent (interface)](#interactioncomponent-interface)
  - [ColorComponent (interface)](#colorcomponent-interface)
  - [ComponentType (enum)](#componenttype-enum)
  - [Orientation (enum)](#orientation-enum)
  - [Message](#message)
- [Display](#display)
  - [GraphicalWindow](#graphicalwindow)

---

## Configuration

### IniFile

**Package:** `fr.emse.fayol.maqit.simulator.configuration`

Reads `.ini` configuration files using the `org.ini4j` library.

```java
public class IniFile {
    // Constructor
    public IniFile(String filename) throws Exception;

    // Methods — read values by [section] and key
    public int getIntValue(String section, String key);
    public double getDoubleValue(String section, String key);
    public String getStringValue(String section, String key);
    public java.awt.Color getColorValue(String section, String key);
}
```

**Usage:**
```java
IniFile ifile = new IniFile("configuration.ini");
int rows = ifile.getIntValue("environment", "rows");
```

---

### SimProperties

**Package:** `fr.emse.fayol.maqit.simulator.configuration`

Holds all simulation configuration parameters as **public fields**. Populated by calling `simulationParams()` and `displayParams()` after construction.

```java
public class SimProperties {
    // Constructor
    public SimProperties(IniFile ifile);

    // --- [configuration] section fields ---
    public int display;         // 1 = show GUI, 0 = headless
    public int simulation;      // 1 = simulation mode
    public int mqtt;            // 1 = enable MQTT
    public int debug;           // 1 = debug mode
    public int waittime;        // ms delay between simulation steps
    public int step;            // total simulation steps
    public int seed;            // random seed
    public int field;           // robot perception field range
    public int nbrobot;         // number of robots
    public int nbobstacle;      // number of obstacles

    // --- [environment] section fields ---
    public int rows;            // grid rows
    public int columns;         // grid columns

    // --- [display] section fields ---
    public int display_x;       // window X position
    public int display_y;       // window Y position
    public int display_width;   // window width in pixels
    public int display_height;  // window height in pixels
    public String display_title;// window title

    // --- [color] section fields ---
    public java.awt.Color colorrobot;     // RGB color for robots
    public java.awt.Color colorgoal;      // RGB color for goals
    public java.awt.Color colorobstacle;  // RGB color for obstacles
    public java.awt.Color colorother;     // RGB color for "other"
    public java.awt.Color colorunknown;   // RGB color for "unknown"

    // --- Other fields ---
    public int colorComponent;
    public int led;
    public IniFile ifile;
    public String nameMqtt, ipMqtt;
    public int portMqtt;
    public int tableNumber;
    public String tablesName, tablesPosX, tablesPosY, tablesWidth, tablesHeight;

    // Methods
    public void simulationParams();          // loads [configuration], [environment], [color]
    public void displayParams();             // loads [display]
    public void loadMqttParams();            // loads MQTT params
    public void loadLEDTablesParams();       // loads LED table params
    public List<LEDTable> generateLEDTables();
}
```

**Usage:**
```java
IniFile ifile = new IniFile("configuration.ini");
SimProperties sp = new SimProperties(ifile);
sp.simulationParams();
sp.displayParams();
System.out.println("Robots: " + sp.nbrobot);
```

---

### Debug

**Package:** `fr.emse.fayol.maqit.simulator.configuration`

Utility class for debug logging (details not fully explored).

---

### MatrixOp

**Package:** `fr.emse.fayol.maqit.simulator.configuration`

Utility class for matrix operations (details not fully explored).

---

### Mqtt

**Package:** `fr.emse.fayol.maqit.simulator.configuration`

MQTT client configuration (details not fully explored).

---

## Simulator (Factory)

### SimFactory

**Package:** `fr.emse.fayol.maqit.simulator`

Abstract base class for creating simulations. Uses two generic types.

```java
public abstract class SimFactory<
    E extends GridEnvironment,
    J extends SituatedComponent
> {
    // Fields
    protected SimProperties sp;
    protected E environment;
    protected static int idComponent;   // auto-incrementing component ID
    public static int DEBUG;
    public static int ROWS;
    public static int COLUMNS;

    // Constructor
    public SimFactory(SimProperties sp);

    // Abstract methods — MUST be implemented by subclass
    public abstract void createEnvironment();
    public abstract void createObstacle();
    public abstract void createRobot();
    public abstract void createGoal();
    public abstract void addNewComponent(J component);
    public abstract void updateEnvironment(int[] oldPos, int[] newPos, int id);
    public abstract void schedule();
}
```

---

### ColorSimFactory

**Package:** `fr.emse.fayol.maqit.simulator`

Extends `SimFactory<ColorGridEnvironment, ColorSituatedComponent>`. Provides concrete implementations for graphical display, adding components, and updating the environment. **Only `schedule()` remains abstract.**

```java
public abstract class ColorSimFactory
    extends SimFactory<ColorGridEnvironment, ColorSituatedComponent> {

    // Fields
    protected GraphicalWindow gwindow;

    // Constructor
    public ColorSimFactory(SimProperties sp);

    // Concrete methods (already implemented)
    public void initializeGW();      // creates and shows the graphical window
    public void refreshGW();         // refreshes/repaints the graphical window
    public void addNewComponent(ColorSituatedComponent component);
    public void updateEnvironment(int[] oldPos, int[] newPos, int id);

    // Still abstract
    public abstract void schedule();
}
```

**Key insight:** By extending `ColorSimFactory`, you only need to implement:
- `createEnvironment()`
- `createObstacle()`
- `createRobot()`
- `createGoal()`
- `schedule()`

The methods `addNewComponent()`, `updateEnvironment()`, `initializeGW()`, and `refreshGW()` are already provided.

---

## Environment

### Cell

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Abstract base class for grid cells.

---

### SimpleCell

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Extends `Cell`. Basic cell implementation.

---

### ColorSimpleCell

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Extends `SimpleCell`. A cell that stores color information for rendering.

---

### ComposedCell

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Extends `Cell`. A cell that can hold multiple components.

---

### GridEnvironment

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Abstract base class for grid-based environments.

```java
public abstract class GridEnvironment<
    E extends Cell,
    T extends SituatedComponent,
    R extends MobileComponent
> {
    // Fields
    protected E[][] grid;
    protected Random rnd;

    // Constructor
    public GridEnvironment(int seed, Class<E> type, Supplier<E> supplier);

    // Methods
    public E[][] getGrid();
    public void initializeGrid();
    public E getCell(int x, int y);
    public E[][] getNeighbor(int x, int y, int field);  // get perception grid
    protected boolean validCell(int x, int y);
    protected Goal getGoal(int x, int y);

    // Abstract methods
    public abstract List<R> getRobot();
    public abstract int[] getPlace();                     // find a random free cell
    public abstract void removeCellContent(int x, int y);
    public abstract void setCellContent(int x, int y, T component);
}
```

---

### SimpleGridEnvironment

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Extends `GridEnvironment`. Basic grid environment.

---

### ColorGridEnvironment

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Extends `GridEnvironment<ColorSimpleCell, ColorSituatedComponent, ColorRobot>`. The primary environment class used in this project.

```java
public class ColorGridEnvironment
    extends GridEnvironment<ColorSimpleCell, ColorSituatedComponent, ColorRobot<ColorSimpleCell>> {

    // Constructor
    public ColorGridEnvironment(int seed);

    // Methods
    public List<ColorRobot<ColorSimpleCell>> getRobot();   // get all robots
    public int[] getPlace();                                // random free position [x, y]
    public void setCellContent(int x, int y, ColorSituatedComponent comp);
    public void removeCellContent(int x, int y);
    public void moveComponent(int oldX, int oldY, int newX, int newY);
    public void moveComponent(int[] oldPos, int[] newPos);
    public int[] getCellColor(int x, int y);
    public int[] getCellGoalColor(int x, int y);
}
```

**Usage:**
```java
this.environment = new ColorGridEnvironment(this.sp.seed);
int[] freePos = this.environment.getPlace();  // returns [row, col]
```

---

### Location

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Simple class representing a position (x, y) on the grid.

---

### Goal / ColorGoal

**Package:** `fr.emse.fayol.maqit.simulator.environment`

Represents goal positions on the grid.

---

## Components

### SituatedComponent

**Package:** `fr.emse.fayol.maqit.simulator.components`

Abstract base for anything placed on the grid.

```java
public abstract class SituatedComponent {
    // Fields
    protected int x;
    protected int y;
    protected final int id;          // auto-assigned unique ID
    protected static int INDEX_ID;   // static counter

    // Constructors
    public SituatedComponent();
    public SituatedComponent(int[] pos);  // pos = [x, y]

    // Methods
    public int getId();
    public int getX();
    public int getY();
    public void setX(int x);
    public void setY(int y);
    public void setLocation(int[] pos);
    public int[] getLocation();           // returns [x, y]
    public abstract ComponentType getComponentType();
}
```

---

### ColorSituatedComponent

**Package:** `fr.emse.fayol.maqit.simulator.components`

Extends `SituatedComponent`, implements `ColorComponent`. Adds RGB color.

```java
public abstract class ColorSituatedComponent extends SituatedComponent
    implements ColorComponent {

    // Fields
    protected int[] rgb;   // [r, g, b]

    // Constructors
    public ColorSituatedComponent(int[] pos);                // pos = [x, y]
    public ColorSituatedComponent(int[] pos, int[] rgb);     // pos + color

    // Methods
    public int[] getColor();
    public void setColor(int[] rgb);
}
```

---

### Obstacle / ColorObstacle

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
// Obstacle — extends SituatedComponent
public class Obstacle extends SituatedComponent { ... }

// ColorObstacle — extends ColorSituatedComponent
public class ColorObstacle extends ColorSituatedComponent {
    public ColorObstacle(int[] pos);              // pos = [x, y]
    public ColorObstacle(int[] pos, int[] rgb);   // pos + color
    public ComponentType getComponentType();       // returns OBSTACLE
}
```

**Usage:**
```java
int[] pos = this.environment.getPlace();
int[] rgb = {sp.colorobstacle.getRed(), sp.colorobstacle.getGreen(), sp.colorobstacle.getBlue()};
ColorObstacle obs = new ColorObstacle(pos, rgb);
addNewComponent(obs);
```

---

### Robot

**Package:** `fr.emse.fayol.maqit.simulator.components`

Abstract base robot class.

```java
public abstract class Robot<E extends Cell>
    extends SituatedComponent implements MobileComponent {

    // Fields
    protected String name;
    protected Orientation orientation;
    protected boolean goalReached;
    protected int field;             // perception range
    protected E[][] grid;            // local perception grid

    // Constructors
    protected Robot(String name, int field);
    protected Robot(String name, int field, int[] pos);

    // Movement
    public void turnLeft();
    public void turnRight();
    public boolean moveForward();    // returns true if moved
    public boolean moveBackward();   // returns true if moved

    // Perception
    public void updatePerception(E[][] grid);
    protected HashMap<String, Location> getNextCoordinate();

    // Getters/Setters
    public int getField();
    public String getName();
    public Orientation getCurrentOrientation();
    public void setCurrentOrientation(Orientation o);
    public boolean isGoalReached();
    public void setGoalReached(boolean reached);
    public void randomOrientation();
}
```

---

### ColorRobot

**Package:** `fr.emse.fayol.maqit.simulator.components`

Extends `ColorSituatedComponent`, implements `MobileComponent`. Adds color support to robots.

```java
public abstract class ColorRobot<E extends Cell>
    extends ColorSituatedComponent implements MobileComponent {

    // Fields (same as Robot)
    protected String name;
    protected Orientation orientation;
    protected boolean goalReached;
    protected int field;
    protected E[][] grid;

    // Constructors
    protected ColorRobot(String name, int field, int[] pos);
    protected ColorRobot(String name, int field, int[] pos, int[] rgb);

    // Movement
    public void turnLeft();
    public void turnRight();
    public boolean moveForward();
    public boolean moveBackward();

    // Perception
    public void updatePerception(E[][] grid);
    protected HashMap<String, Location> getNextCoordinate();

    // Getters/Setters
    public int getField();
    public String getName();
    public void setName(String name);
    public Orientation getCurrentOrientation();
    public void setCurrentOrientation(Orientation o);
    public boolean isGoalReached();
    public void setGoalReached(boolean reached);
    public void randomOrientation();
}
```

---

### InteractionRobot

**Package:** `fr.emse.fayol.maqit.simulator.components`

Extends `Robot`, implements `InteractionComponent`. Adds messaging to basic robots.

```java
public abstract class InteractionRobot<E extends Cell>
    extends Robot<E> implements InteractionComponent { ... }
```

---

### ColorInteractionRobot

**Package:** `fr.emse.fayol.maqit.simulator.components`

Extends `ColorRobot`, implements `InteractionComponent`. **This is the class you should extend to create your robot.**

```java
public abstract class ColorInteractionRobot<E extends Cell>
    extends ColorRobot<E> implements InteractionComponent {

    // Fields
    protected Map<Integer, List<Message>> messages;  // received messages by emitter ID
    public List<Message> sentMessages;               // outgoing messages

    // Constructors
    protected ColorInteractionRobot(String name, int field, int[] pos);
    protected ColorInteractionRobot(String name, int field, int[] pos, int[] rgb);

    // Messaging
    public void sendMessage(Message msg);            // queues message for sending
    public void receiveMessage(Message msg);         // receives incoming message
    public void readMessages();                      // processes all received messages
    public List<Message> searchMessages(int emitterId);
    public List<Message> popSentMessages();          // returns & clears sent messages
}
```

**Usage:**
```java
public class BasicRobot extends ColorInteractionRobot<ColorSimpleCell> {
    public BasicRobot(String name, int field, int debug, int[] pos,
                      Color co, int rows, int columns) {
        super(name, field, pos,
              new int[]{co.getRed(), co.getGreen(), co.getBlue()});
        // store rows, columns, debug as needed
    }

    public void move(int nb) {
        // implement movement logic
    }
}
```

---

### MobileComponent (interface)

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public interface MobileComponent {
    void turnLeft();
    void turnRight();
    boolean moveForward();
    boolean moveBackward();
    void move(int nb);              // custom movement logic (you implement this)
}
```

---

### InteractionComponent (interface)

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public interface InteractionComponent {
    void sendMessage(Message msg);
    void receiveMessage(Message msg);
    void readMessages();
    void handleMessage(Message msg);  // process a single received message
}
```

---

### ColorComponent (interface)

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public interface ColorComponent {
    int[] getColor();
    void setColor(int[] rgb);
}
```

---

### ComponentType (enum)

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public enum ComponentType {
    ROBOT,
    OBSTACLE,
    GOAL,
    // possibly others
}
```

---

### Orientation (enum)

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public enum Orientation {
    NORTH,
    SOUTH,
    EAST,
    WEST
}
```

> Orientation determines which direction is "forward" for `moveForward()` / `moveBackward()`.

---

### Message

**Package:** `fr.emse.fayol.maqit.simulator.components`

```java
public class Message {
    // Fields
    private int emitter;      // sender robot ID
    private int receiver;     // receiver robot ID (0 = broadcast)
    private String content;   // message body

    // Constructors
    public Message(int emitter, String content);              // broadcast
    public Message(int emitter, int receiver, String content); // directed

    // Getters/Setters
    public int getEmitter();
    public void setEmitter(int id);
    public int getReceiver();
    public void setReceiver(int id);
    public String getContent();
    public void setContent(String content);
}
```

---

## Display

### GraphicalWindow

**Package:** `fr.emse.fayol.maqit.simulator.display`

Swing-based GUI window for rendering the grid.

```java
public class GraphicalWindow {
    // Constructor
    public GraphicalWindow(
        ColorSimpleCell[][] grid,
        int x, int y,              // window position
        int width, int height,     // window size
        String title
    );

    // Methods
    public void init();       // show the window
    public void refresh();    // repaint the grid
}
```

**Note:** You typically don't use `GraphicalWindow` directly. `ColorSimFactory` provides `initializeGW()` and `refreshGW()` which wrap these calls.

---

## Class Hierarchy Diagram

```
SituatedComponent (abstract)
├── Obstacle
├── ColorSituatedComponent (abstract, + ColorComponent)
│   ├── ColorObstacle
│   ├── ColorRobot<E> (abstract, + MobileComponent)
│   │   └── ColorInteractionRobot<E> (abstract, + InteractionComponent)
│   │       └── BasicRobot (YOUR CLASS)
│   └── ImageSituatedComponent
└── Robot<E> (abstract, + MobileComponent)
    └── InteractionRobot<E> (abstract, + InteractionComponent)

GridEnvironment<E, T, R> (abstract)
├── SimpleGridEnvironment
└── ColorGridEnvironment

SimFactory<E, J> (abstract)
└── ColorSimFactory (abstract)
    └── BasicSimulator (YOUR CLASS)
```

---

## Full Package Listing

```
fr.emse.fayol.maqit.simulator/
├── SimFactory.class
├── ColorSimFactory.class
├── configuration/
│   ├── IniFile.class
│   ├── SimProperties.class
│   ├── Debug.class
│   ├── MatrixOp.class
│   ├── Mqtt.class
│   └── RosbridgeClient.class
├── components/
│   ├── SituatedComponent.class
│   ├── ColorSituatedComponent.class
│   ├── ImageSituatedComponent.class
│   ├── Obstacle.class
│   ├── ColorObstacle.class
│   ├── Robot.class
│   ├── ColorRobot.class
│   ├── InteractionRobot.class
│   ├── ColorInteractionRobot.class
│   ├── Message.class
│   ├── Orientation.class
│   ├── ComponentType.class
│   ├── MobileComponent.class (interface)
│   ├── InteractionComponent.class (interface)
│   └── ColorComponent.class (interface)
├── environment/
│   ├── Cell.class
│   ├── SimpleCell.class
│   ├── ColorSimpleCell.class
│   ├── ComposedCell.class
│   ├── GridEnvironment.class
│   ├── SimpleGridEnvironment.class
│   ├── ColorGridEnvironment.class
│   ├── Location.class
│   ├── Goal.class
│   └── ColorGoal.class
└── display/
    ├── GraphicalWindow.class
    ├── ImageProvider.class
    ├── CeladonCity.class
    ├── LEDTable.class
    └── LEDColorGridEnvironment.class
```
