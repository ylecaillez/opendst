package com.pingidentity.opendst.maven;

import static java.lang.Double.compare;
import static java.lang.Long.parseLong;
import static java.lang.Math.abs;
import static java.lang.Math.log;
import static java.lang.Math.max;
import static java.lang.Math.round;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.in;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.security.MessageDigest.getInstance;
import static java.util.Comparator.comparing;
import static java.util.Objects.hash;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.ThreadLocalRandom.current;

import java.io.File;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap.BasicEntry;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.jr.ob.JSON;

/**
 * Chose between:
 * - Creating a plan from a brand new timeline
 * - Creating a plan from an existing timeline, simply running it longer
 * - Creating a plan by branching an existing timeline at some interesting moment
 */
final class Orchestrator {
    public static final int NGRAM_DEPTH = 6;

    private final static class NGram {
        private final int capacity;
        private final long[] window;
        private final long powerN;
        private int position;
        private long currentHash;
        private int count;

        NGram(int capacity) {
            this.capacity = capacity;
            this.window = new long[capacity];
            long pn = 1;
            for (int i = 0; i < capacity - 1; i++) {
                pn *= 31;
            }
            this.powerN = pn;
        }

        long add(long signal) {
            if (count < capacity) {
                currentHash = currentHash * 31 + signal;
                window[count] = signal;
                count++;
                return currentHash;
            }
            // Slide the window: Remove oldest, Add newest
            long oldest = window[position];
            currentHash = (currentHash - oldest * powerN) * 31 + signal;
            window[position] = signal;
            position = (position + 1) % capacity;
            return currentHash;
        }

        public void reset() {
            currentHash = position = count = 0;
        }
    }

    private record LogStatement (String rid, long it, String source, LogMessage log) {
        boolean isValid() {
            return rid != null && source != null && log != null && log.message != null;
        }

        boolean isEndOfSimulation() {
            return "simulator".equals(source) && "Terminated".equalsIgnoreCase(log.message());
        }
    }

    private record LogMessage(int code, int last, String message) { }


    private static long signalFor(String string) {
        try {
            return ByteBuffer.wrap(getInstance("SHA-256").digest(string.getBytes(UTF_8))).getLong();
        } catch (NoSuchAlgorithmException e) {
            // This should never happen as SHA-256 support is mandatory for JVMs;
            err.println("SHA-256 is not supported by this JVM");
            exit(1);
            throw new UnsupportedOperationException(e);
        }
    }

    record Segment(long seed, long iteration) { }

    record Plan(String rid, List<Segment> segments) {}

    private static final class Timeline {
        private final long id;
        private final NGram signalSequence;
        private String lastLogLine;
        private Branch branch;

        Timeline(long id, Branch branch) {
            this.id = id;
            this.branch = branch;
            this.signalSequence = new NGram(NGRAM_DEPTH);
        }

        long id() {
            return id;
        }

        String lastLogLineOrNull() {
            return lastLogLine;
        }

        long addSignal(long signal) {
            return signalSequence.add(signal);
        }

        void setLastLogLine(String lastLogLine) {
            this.lastLogLine = lastLogLine;
        }

        Timeline runLonger(long increment) {
            signalSequence.reset();
            branch = branch.extendBy(increment);
            lastLogLine = null;
            return this;
        }

        Moment getMomentAt(long iteration) {
            return Moment.from(this, iteration);
        }

        Plan plan() {
            var segments = new ArrayList<Segment>();
            segments.add(new Segment(branch.seed, branch.lastIteration));
            for (var current = branch.moment; !current.isRoot(); current = current.timeline.branch.moment()) {
                segments.add(new Segment(current.timeline.branch.seed, current.iteration));
            }
            return new Plan(Long.toString(id), List.copyOf(segments.reversed()));
        }
    }

    private static final class Moment implements Comparable<Moment> {
        private final Timeline timeline;
        private final long iteration;
        private double pheromones;

        static Moment root() {
            return new Moment(null, 0);
        }

        static Moment from(Timeline timeline, long iteration) {
            if (iteration <= 0) {
                throw new IllegalArgumentException("iteration must be positive");
            }
            return new Moment(timeline, iteration);
        }

        private Moment(Timeline timeline, long iteration) {
            this.timeline = timeline;
            this.iteration = iteration;
        }

        long iteration() {
            return iteration;
        }

        boolean isRoot() {
            return timeline == null;
        }

        Branch branch(long dynamicDuration) {
            return new Branch(this, current().nextLong(), iteration + dynamicDuration);
        }

        double pheromones() {
            return pheromones;
        }

        void incrementPheromones(double increment) {
            pheromones += increment;
        }

        void evaporatePheromones() {
            pheromones *= 0.90;
        }

        @Override
        public int hashCode() {
            return hash(timeline.id(), iteration);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Moment m && Objects.equals(timeline.id(), m.timeline.id()) && iteration == m.iteration;
        }

        @Override
        public int compareTo(Moment o) {
            return compare(pheromones, o.pheromones);
        }

        Timeline timeline() {
            return timeline;
        }
    }

    private record Branch(Moment moment, long seed, long lastIteration) {
        static Branch root(long endIteration) {
            return new Branch(Moment.root(), current().nextLong(), endIteration);
        }

        Branch extendBy(long increment) {
            return new Branch(moment, seed, lastIteration + increment);
        }
    }

    /** Track the moment at witch signals have been received */
    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private final List<Pattern> compiledFailurePatterns = new ArrayList<>();
    private final BloomFilter boringSequences = new BloomFilter(1_000_000, 0.01);
    private final Long2ObjectOpenHashMap<Moment> interestingSequences = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Timeline> runningTimelines = new Long2ObjectOpenHashMap<>();
    private final Random random = new Random();

    private static final long BASE_ITERATIONS = 100;
    private static final long MAX_ITERATIONS = 5000;
    private long timelineCount;
    private long consecutiveNonDiscoveries;
    private long runSinceLastPrune;

    Orchestrator(Collection<String> signalLogPatterns, Collection<String> failureLogPatterns) {
        if (signalLogPatterns != null) {
            signalLogPatterns.stream().map(Pattern::compile).forEach(compiledPatterns::add);
        }
        if (failureLogPatterns != null) {
            failureLogPatterns.stream().map(Pattern::compile).forEach(compiledFailurePatterns::add);
        }
    }

    synchronized Plan nextPlan() {
        new HashSet<>(interestingSequences.values()).forEach(Moment::evaporatePheromones);
        int size = interestingSequences.size();
        interestingSequences.values().removeIf(m -> {
            if (m.pheromones <= 0.1) {
                boringSequences.add(m.timeline().signalSequence.currentHash);
                return true;
            }
            return false;
        });
        if (interestingSequences.size() != size) {
            System.err.printf("Interesting sequences have been pruned from %d to %d%n", size,
                              interestingSequences.size());
        }
        var timeline = selectOrCreateTimeline();
        runningTimelines.put(timeline.id(), timeline);
        return timeline.plan();
    }

    private Timeline selectOrCreateTimeline() {
        long dynamicDuration = Math.min(MAX_ITERATIONS, BASE_ITERATIONS + (consecutiveNonDiscoveries * 10));
        var interestingMoment = selectInterestingMomentOrNull();
        int selector = random.nextInt(100);
        if (interestingMoment != null && selector < 60) {
            return new Timeline(++timelineCount, interestingMoment.branch(dynamicDuration));
        } else if (interestingMoment != null && selector < 80 && runningTimelines.containsKey(
                interestingMoment.timeline().id())) {
            timelineCount++;
            return interestingMoment.timeline().runLonger(dynamicDuration);
        } else {
            var tl = new Timeline(++timelineCount, Branch.root(dynamicDuration));
            double max = interestingSequences.values().stream().mapToDouble(Moment::pheromones).max().orElse(100);
            tl.branch.moment().incrementPheromones(max);
            return tl;
        }
    }

    synchronized void onLogReceived(Plan plan, String logLine) {
        var log = parseLogOrNull(logLine);
        if (log == null || !log.isValid()) {
            err.printf("Warning: Incorrectly formatted log message: %s. Skipped.%n", logLine);
            return;
        }
        // TODO: Remove this hashmap lookup from hot path
        var timeline = runningTimelines.get(Long.parseLong(plan.rid()));
        if (timeline == null) {
            err.printf("BUG: No timeline found for plan: %s%n", JSON.std.asString(plan));
            exit(1);
        } else if (log.isEndOfSimulation() && nonDeterminismDetected(timeline, plan, logLine)) {
            exit(1);
        } else if (failurePatternDetected(plan, logLine, log)) {
            // TODO: Add option to continue on failure ?
            exit(1);
        } else {
            detectSignal(timeline, log.it(), logLine);
        }
    }

    private static LogStatement parseLogOrNull(String line) {
        try {
            return JSON.std.beanFrom(LogStatement.class, line);
        } catch (JacksonException e) {
            return null;
        }
    }

    private boolean nonDeterminismDetected(Timeline timeline, Plan plan, String line) {
        var previousRun = timeline.lastLogLineOrNull();
        if (previousRun == null) {
            // No information about previous run: cannot validate determinism
            return false;
        } else if (!previousRun.equals(line)) {
            var pathOrNull = savePlanOrNull(plan);
            if (pathOrNull != null) {
                err.printf("A non-deterministic execution has been detected. "
                         + "Plan saved to '%s' (expecting=%s, current=%s%n)", pathOrNull, previousRun, line);
            } else {
                err.printf("A non-deterministic execution has been detected (expecting=%s, current=%s%n). Plan=%s%n",
                           previousRun, line, JSON.std.asString(plan));
            }
            return true;
        } else {
            timeline.setLastLogLine(line);
            return false;
        }
    }

    private boolean failurePatternDetected(Plan plan, String logLine, LogStatement log) {
        for (var pattern : compiledFailurePatterns) {
            if (pattern.matcher(log.log.message).find()) {
                var pathOrNull = savePlanOrNull(plan);
                if (pathOrNull != null) {
                    err.printf("The failure pattern '%s' has been detected at trial %d in '%s'. Plan saved to '%s'%n",
                               pattern, timelineCount, logLine, pathOrNull);
                } else {
                    err.printf("The failure pattern '%s' has been detected at trial %d in '%s'. Plan '%s'%n",
                               pattern, timelineCount, logLine, JSON.std.asString(plan));
                }
                return true;
            }
        }
        return false;
    }

    private void detectSignal(Timeline timeline, long iteration, String log) {
        for (var pattern : compiledPatterns) {
            var matcher = pattern.matcher(log);
            if (matcher.find()) {
                String found =  log.substring(matcher.start(), matcher.end());
                long sequence = timeline.addSignal(signalFor(found));
                if (boringSequences.mightContain(sequence)) {
                    continue;
                }
                var bestKnownMoment = interestingSequences.get(sequence);
                if (bestKnownMoment == null || iteration < bestKnownMoment.iteration()) {
                    consecutiveNonDiscoveries = 0;
                    var moment = timeline.getMomentAt(iteration);
                    double max = interestingSequences.values().stream().mapToDouble(Moment::pheromones).max().orElse(100);
                    moment.incrementPheromones(max * 1.2);
                    interestingSequences.put(sequence, moment);
                }
            }
        }
    }

    synchronized void onPlanTerminated(Plan plan, int code) {
        var timelineOrNull = runningTimelines.remove(parseLong(plan.rid()));
        if (timelineOrNull == null) {
            err.printf("BUG: A plan has terminated with code '%d' but it has no running timeline associated%n", code);
            exit(1);
        }

        consecutiveNonDiscoveries++;
        runSinceLastPrune++;
        if (runSinceLastPrune >= 500) {
            runSinceLastPrune = 0;
            if (interestingSequences.size() >= 100_000) {
                // Remove the 10k most boring sequences
                var boring = new TreeSet<Long2ObjectMap.Entry<Moment>>(comparing(e -> e.getValue().pheromones()));
                interestingSequences.forEach((sequence, moment) -> {
                    boring.add(new BasicEntry<>(sequence, moment));
                    if (boring.size() >= 10_000) {
                        boring.removeLast();
                    }
                });
                boring.forEach(e -> {
                    long sequence = e.getLongKey();
                    interestingSequences.remove(sequence);
                    boringSequences.add(sequence);
                });
            }
        }

        if (code != 0) {
            var pathOrNull = savePlanOrNull(plan);
            if (pathOrNull != null) {
                err.printf("The simulation has terminated unexpectedly with error code '%d'. Plan saved to '%s'%n",
                           code, pathOrNull);
            } else {
                err.printf("The simulation has terminated unexpectedly with error code '%d'. Plan '%s'%n",
                           code, JSON.std.asString(plan));
            }
        }
    }

    private static String savePlanOrNull(Plan plan) {
        try {
            var failureDir = new File("target/opendst/failures");
            failureDir.mkdirs();
            var failureFile = new File(failureDir, "failure-%s.json".formatted(randomUUID()));
            JSON.std.write(plan, failureFile);
            return failureFile.getAbsolutePath();
        } catch (Exception e) {
            err.println("Failed to save plan: " + e.getMessage());
            return null;
        }
    }

    private Moment selectInterestingMomentOrNull() {
        var eligible = new HashSet<>(interestingSequences.values());
        eligible.removeIf(m -> runningTimelines.containsKey(m.timeline.id()));
        double sumIntensity = eligible.stream().mapToDouble(Moment::pheromones).sum();
        double maxIntensity = eligible.stream().mapToDouble(Moment::pheromones).max().orElse(0.0);
        if (sumIntensity == 0.0) {
            return null;
        }
        double pick = random.nextDouble(0, sumIntensity);
        double intensity = 0.0;
        for (var moment : eligible) {
            intensity += moment.pheromones();
            if (intensity >= pick) {
                System.out.printf("Select interesting moment from %d/%d sum %.4f: %.4f/%.4f%n", eligible.size(),
                                  interestingSequences.size(), sumIntensity, moment.pheromones(), maxIntensity);
                return moment;
            }
        }
        return null;
    }

    private static final class BloomFilter {
        private final BitSet bits;
        private final int numberOfBits;
        private final int numberOfHashes;

        BloomFilter(int expectedElements, double fpp) {
            this.numberOfBits = (int) (-expectedElements * log(fpp) / (log(2) * log(2)));
            this.numberOfHashes = max(1, (int) round((double) numberOfBits / expectedElements * log(2)));
            this.bits = new BitSet(numberOfBits);
        }

        void add(long hash) {
            int h1 = (int) hash;
            int h2 = (int) (hash >>> 32);
            for (int i = 1; i <= numberOfHashes; i++) {
                int combinedHash = h1 + (i * h2);
                bits.set(abs(combinedHash % numberOfBits));
            }
        }

        boolean mightContain(long hash) {
            int h1 = (int) hash;
            int h2 = (int) (hash >>> 32);
            for (int i = 1; i <= numberOfHashes; i++) {
                int combinedHash = h1 + (i * h2);
                if (!bits.get(abs(combinedHash % numberOfBits))) {
                    return false;
                }
            }
            return true;
        }
    }
}
