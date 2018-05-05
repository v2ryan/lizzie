package wagner.stephanie.lizzie.analysis;

import jdk.nashorn.internal.runtime.arrays.ArrayIndex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import wagner.stephanie.lizzie.Lizzie;
import wagner.stephanie.lizzie.rules.Stone;

import javax.swing.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * an interface with leelaz.exe go engine. Can be adapted for GTP, but is specifically designed for GCP's Leela Zero.
 * leelaz is modified to output information as it ponders
 * see www.github.com/gcp/leela-zero
 */
public class Leelaz {
    private static final long MINUTE = 60 * 1000; // number of milliseconds in a minute
    //    private static final long SECOND = 1000;
    private long maxAnalyzeTimeMillis;//, maxThinkingTimeMillis;

    private Process process;

    private BufferedInputStream inputStream;
    private BufferedOutputStream outputStream;

    private boolean printCommunication;
    private boolean isValidVersion = false;

    private boolean isReadingPonderOutput;
    private List<MoveData> bestMoves;
    private List<MoveData> bestMovesTemp;

    private boolean isPondering;
    private long startPonderTime;

    // True while we're waiting for old ponder output to get flushed
    private boolean isWaitingToStartPonder = false;

    // fixed_handicap
    public boolean isSettingHandicap = false;

    // genmove
    public boolean isThinking = false;

    /**
     * Initializes the leelaz process and starts reading output
     *
     * @throws IOException
     */
    public Leelaz() throws IOException, JSONException {
        isReadingPonderOutput = false;
        bestMoves = new ArrayList<>();
        bestMovesTemp = new ArrayList<>();

        isPondering = false;
        startPonderTime = System.currentTimeMillis();

        JSONObject config = Lizzie.config.config.getJSONObject("leelaz");

        printCommunication = config.getBoolean("print-comms");
        maxAnalyzeTimeMillis = MINUTE * config.getInt("max-analyze-time-minutes");
//        maxThinkingTimeMillis = SECOND * config.getInt("max-game-thinking-time-seconds");

        // list of commands for the leelaz process
        List<String> commands = new ArrayList<>();
        commands.add("./leelaz"); // windows, linux, mac all understand this
        commands.add("-g");
        commands.add("-t");
        commands.add(""+config.getInt("threads"));
        commands.add("-w");
        commands.add(config.getString("weights"));
        commands.add("-b");
        commands.add("0");

//        if (config.getBoolean("noise")) {
//            commands.add("-n");
//        }

        try {
            JSONArray gpu = config.getJSONArray("gpu");

            for (int i = 0; i < gpu.length(); i++) {
                commands.add("--gpu");
                commands.add(String.valueOf(gpu.getInt(i)));
            }
        } catch (Exception e) {
            // Nothing
        }

        // run leelaz
        ProcessBuilder processBuilder = new ProcessBuilder(commands);
        processBuilder.directory(new File("."));
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();

        initializeStreams();

        new Thread(this::read).start();
    }

    /**
     * Initializes the input and output streams
     */
    private void initializeStreams() {
        inputStream = new BufferedInputStream(process.getInputStream());
        outputStream = new BufferedOutputStream(process.getOutputStream());
    }

    /**
     * Parse a line of Leelaz output
     *
     * @param line output line
     */
    private void parseLine(String line) {
        synchronized (this) {
            boolean isMoveDataLine = line.matches("(?s) +[A-T][0-9]+ -> +[0-9].*");
            // System.out.println(" # " + isMoveDataLine + " |" + line); System.out.flush();
            if (!isReadingPonderOutput && isMoveDataLine && !isWaitingToStartPonder) {
                if (System.currentTimeMillis() - startPonderTime > maxAnalyzeTimeMillis) {
                    // we have pondered for enough time. pause pondering
                    togglePonder();
                }

                isReadingPonderOutput = true;
                bestMovesTemp = new ArrayList<>();
                parseMoveDataLine(line);
            } else if (isReadingPonderOutput && !isMoveDataLine && !isWaitingToStartPonder) {
                isReadingPonderOutput = false;
                bestMoves = bestMovesTemp;

                if (Lizzie.frame != null) Lizzie.frame.repaint();
                if (isPondering) {
                    startPonder();
                }
            } else {

                if (isReadingPonderOutput && !isWaitingToStartPonder) {
                    parseMoveDataLine(line);
                } else {
                    if (printCommunication) {
                        System.out.print(line);
                    }

                    line = line.trim();
                    if (Lizzie.frame != null && line.startsWith("=") && line.length() > 2) {

                        if (isWaitingToStartPonder) {
                            // Now we can tell it to actually start pondering
                            startPonder();
                        }

                        if (isSettingHandicap) {
                            line = line.substring(2);
                            String[] stones = line.split(" ");
                            for (String stone : stones) {
                                int[] coordinates = Lizzie.board.convertNameToCoordinates(stone);
                                Lizzie.board.getHistory().setStone(coordinates, Stone.BLACK);
                            }
                            isSettingHandicap = false;
                        } else if (isThinking) {
                            if (Lizzie.frame.isPlayingAgainstLeelaz) {
                                Lizzie.board.place(line.substring(2));
                            }
                            isThinking = false;
                        }
                    }
                }
            }
        }
    }

    /**
     * Parse a move-info line of Leelaz output
     *
     * @param line output line
     */
    private void parseMoveDataLine(String line) {
        line = line.trim();
        // ignore passes, and only accept lines that start with a coordinate letter
        if (line.length() > 0 && Character.isLetter(line.charAt(0)) && !line.startsWith("pass")) {
            if (!(Lizzie.frame != null && Lizzie.frame.isPlayingAgainstLeelaz && Lizzie.frame.playerIsBlack != Lizzie.board.getData().blackToPlay)) {
                try {
                    bestMovesTemp.add(new MoveData(line));
                } catch (ArrayIndexOutOfBoundsException e) {
                    // this is very rare but is possible. ignore
                }
            }
        }
    }

    /**
     * Tell Leelaz to start pondering
     *
     */
    private void startPonder() {
        sendCommand("time_left b 0 0");
        isWaitingToStartPonder = false;
        Timer ponderStopTimer = new Timer();
        ponderStopTimer.schedule(new ponderStopTask(), 1000);
    }

    class ponderStopTask extends TimerTask {
        public void run() {
            // Send any command to terminate pondering.
            sendCommand("name");
            isReadingPonderOutput = false;
        }
    }

    /**
     * Continually reads and processes output from leelaz
     */
    private void read() {
        try {
            int c;
            StringBuilder line = new StringBuilder();
            while ((c = inputStream.read()) != -1) {
                line.append((char) c);
                if ((c == '\n')) {
                    parseLine(line.toString());
                    line = new StringBuilder();
                }
            }
            // this line will be reached when Leelaz shuts down
            System.out.println("Leelaz process ended.");

            shutdown();
            System.exit(-1);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * Sends a command for leelaz to execute
     *
     * @param command a GTP command containing no newline characters
     */
    public void sendCommand(String command) {
        if (printCommunication) {
            System.out.printf("> %s\n", command);
        }
        if (command.startsWith("fixed_handicap"))
            isSettingHandicap = true;
        if (command.startsWith("genmove"))
            isThinking = true;
        try {
            outputStream.write((command + "\n").getBytes());
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param color color of stone to play
     * @param move  coordinate of the coordinate
     */
    public void playMove(Stone color, String move) {
        synchronized (this) {
            String colorString;
            switch (color) {
                case BLACK:
                    colorString = "B";
                    break;
                case WHITE:
                    colorString = "W";
                    break;
                default:
                    throw new IllegalArgumentException("The stone color must be B or W, but was " + color.toString());
            }

            sendCommand("play " + colorString + " " + move);
            bestMoves = new ArrayList<>();

            if (isPondering && !Lizzie.frame.isPlayingAgainstLeelaz)
                ponder();
        }
    }

    public void undo() {
        synchronized (this) {
            sendCommand("undo");
            bestMoves = new ArrayList<>();
            if (isPondering)
                ponder();
        }
    }

    /**
     * this initializes leelaz's pondering mode at its current position
     */
    private void ponder() {
        isPondering = true;
        isWaitingToStartPonder = true;
        startPonderTime = System.currentTimeMillis();
        sendCommand("name");    // until it responds to this, incoming ponder results are obsolete
    }

    public void togglePonder() {
        isPondering = !isPondering;
        if (isPondering) {
            ponder();
        } else {
            sendCommand("name"); // ends pondering
        }
    }

    /**
     * End the process
     */
    public void shutdown() {
        process.destroy();
    }

    public List<MoveData> getBestMoves() {
        synchronized (this) {
            return bestMoves;
        }
    }

    public boolean isPondering() {
        return isPondering;
    }

    public class WinrateStats {
        public double maxWinrate;
        public int totalPlayouts;

        public WinrateStats(double maxWinrate, int totalPlayouts) {
            this.maxWinrate = maxWinrate;
            this.totalPlayouts = totalPlayouts;
        }
    }

    /*
     * Return the best win rate and total number of playouts.
     * If no analysis available, win rate is negative and playouts is 0.
     */
    public WinrateStats getWinrateStats() {
        WinrateStats stats = new WinrateStats(-100, 0);

        if (bestMoves != null && !bestMoves.isEmpty()) {
            // we should match the Leelaz UCTNode get_eval, which is a weighted average
            final List<MoveData> moves = bestMoves;

            // get the total number of playouts in moves
            stats.totalPlayouts = moves.stream().reduce(0,
                                                        (Integer result, MoveData move) -> result + move.playouts,
                                                        (Integer a, Integer b) -> a + b);

            // set maxWinrate to the weighted average winrate of moves
            stats.maxWinrate = moves.stream().reduce(0d,
                                                     (Double result, MoveData move) ->
                                                         result + move.winrate * move.playouts / stats.totalPlayouts,
                                                     (Double a, Double b) -> a + b);
        }

        return stats;
    }
}
