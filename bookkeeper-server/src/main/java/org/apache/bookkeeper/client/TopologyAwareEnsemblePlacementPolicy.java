package org.apache.bookkeeper.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.net.NetworkTopology;
import org.apache.bookkeeper.net.NodeBase;
import org.apache.bookkeeper.stats.AlertStatsLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class TopologyAwareEnsemblePlacementPolicy implements ITopologyAwareEnsemblePlacementPolicy<TopologyAwareEnsemblePlacementPolicy.BookieNode> {
    static final Logger LOG = LoggerFactory.getLogger(TopologyAwareEnsemblePlacementPolicy.class);

    protected static class TruePredicate implements Predicate<BookieNode> {

        public static final TruePredicate instance = new TruePredicate();

        @Override
        public boolean apply(BookieNode candidate, Ensemble chosenNodes) {
            return true;
        }

    }

    protected static class EnsembleForReplacementWithNoConstraints implements Ensemble<BookieNode> {

        public static final EnsembleForReplacementWithNoConstraints instance = new EnsembleForReplacementWithNoConstraints();
        static final ArrayList<BookieSocketAddress> EMPTY_LIST = new ArrayList<BookieSocketAddress>(0);

        @Override
        public boolean addNode(BookieNode node) {
            // do nothing
            return true;
        }

        @Override
        public ArrayList<BookieSocketAddress> toList() {
            return EMPTY_LIST;
        }

        /**
         * Validates if an ensemble is valid
         *
         * @return true if the ensemble is valid; false otherwise
         */
        @Override
        public boolean validate() {
            return true;
        }

    }

    protected static class BookieNode extends NodeBase {

        private final BookieSocketAddress addr; // identifier of a bookie node.

        BookieNode(BookieSocketAddress addr, String networkLoc) {
            super(addr.toString(), networkLoc);
            this.addr = addr;
        }

        public BookieSocketAddress getAddr() {
            return addr;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BookieNode)) {
                return false;
            }
            BookieNode other = (BookieNode) obj;
            return getName().equals(other.getName());
        }

        @Override
        public String toString() {
            return String.format("<Bookie:%s>", name);
        }

    }

    /**
     * A predicate checking the rack coverage for write quorum in {@link RoundRobinDistributionSchedule},
     * which ensures that a write quorum should be covered by at least two racks.
     */
    protected static class RRTopologyAwareCoverageEnsemble implements Predicate<BookieNode>, Ensemble<BookieNode> {

        protected interface CoverageSet extends Cloneable {
            boolean apply(BookieNode candidate);
            void addBookie(BookieNode candidate);
            public CoverageSet clone();
        }

        protected class RackQuorumCoverageSet implements CoverageSet {
            HashSet<String> racksOrRegionsInQuorum = new HashSet<String>();
            int seenBookies = 0;

            @Override
            public boolean apply(BookieNode candidate) {
                // If we don't have sufficient members in the write quorum; then we cant enforce
                // rack/region diversity
                if (writeQuorumSize < 2) {
                    return true;
                }

                if (seenBookies + 1 == writeQuorumSize) {
                    return racksOrRegionsInQuorum.size() > (racksOrRegionsInQuorum.contains(candidate.getNetworkLocation(distanceFromLeaves)) ? 1 : 0);
                }
                return true;
            }

            @Override
            public void addBookie(BookieNode candidate) {
                ++seenBookies;
                racksOrRegionsInQuorum.add(candidate.getNetworkLocation(distanceFromLeaves));
            }

            @Override
            public RackQuorumCoverageSet clone() {
                RackQuorumCoverageSet ret = new RackQuorumCoverageSet();
                ret.racksOrRegionsInQuorum = (HashSet<String>)this.racksOrRegionsInQuorum.clone();
                ret.seenBookies = this.seenBookies;
                return ret;
            }
        }

        protected class RackOrRegionDurabilityCoverageSet implements CoverageSet {
            HashMap<String, Integer> allocationToRacksOrRegions = new HashMap<String, Integer>();

            RackOrRegionDurabilityCoverageSet() {
                for (String rackOrRegion: racksOrRegions) {
                    allocationToRacksOrRegions.put(rackOrRegion, 0);
                }
            }

            @Override
            public RackOrRegionDurabilityCoverageSet clone() {
                RackOrRegionDurabilityCoverageSet ret = new RackOrRegionDurabilityCoverageSet();
                ret.allocationToRacksOrRegions = (HashMap<String, Integer>)this.allocationToRacksOrRegions.clone();
                return ret;
            }

            private boolean checkSumOfSubsetWithinLimit(final Set<String> includedRacksOrRegions,
                            final Set<String> remainingRacksOrRegions,
                            int subsetSize,
                            int maxAllowedSum) {
                if (remainingRacksOrRegions.isEmpty() || (subsetSize <= 0)) {
                    if (maxAllowedSum < 0) {
                        LOG.trace("CHECK FAILED: RacksOrRegions Included {} Remaining {}, subsetSize {}, maxAllowedSum {}", new Object[]{
                            includedRacksOrRegions, remainingRacksOrRegions, subsetSize, maxAllowedSum
                        });
                    }
                    return (maxAllowedSum >= 0);
                }

                for(String rackOrRegion: remainingRacksOrRegions) {
                    Integer currentAllocation = allocationToRacksOrRegions.get(rackOrRegion);
                    if (currentAllocation == null) {
                        allocationToRacksOrRegions.put(rackOrRegion, 0);
                        currentAllocation = 0;
                    }

                    if (currentAllocation > maxAllowedSum) {
                        LOG.trace("CHECK FAILED: RacksOrRegions Included {} Candidate {}, subsetSize {}, maxAllowedSum {}", new Object[]{
                            includedRacksOrRegions, rackOrRegion, subsetSize, maxAllowedSum
                        });
                        return false;
                    } else {
                        Set<String> remainingElements = new HashSet<String>(remainingRacksOrRegions);
                        Set<String> includedElements = new HashSet<String>(includedRacksOrRegions);
                        includedElements.add(rackOrRegion);
                        remainingElements.remove(rackOrRegion);
                        if (!checkSumOfSubsetWithinLimit(includedElements,
                            remainingElements,
                            subsetSize - 1,
                            maxAllowedSum - currentAllocation)) {
                            return false;
                        }
                    }
                }

                return true;
            }

            @Override
            public boolean apply(BookieNode candidate) {
                if (minRacksOrRegionsForDurability <= 1) {
                    return true;
                }

                String candidateRackOrRegion = candidate.getNetworkLocation(distanceFromLeaves);
                candidateRackOrRegion = candidateRackOrRegion.startsWith(NodeBase.PATH_SEPARATOR_STR) ? candidateRackOrRegion.substring(1) : candidateRackOrRegion;
                final Set<String> remainingRacksOrRegions = new HashSet<String>(racksOrRegions);
                remainingRacksOrRegions.remove(candidateRackOrRegion);
                final Set<String> includedRacksOrRegions = new HashSet<String>();
                includedRacksOrRegions.add(candidateRackOrRegion);

                // If minRacksOrRegionsForDurability are required for durability; we must ensure that
                // no subset of (minRacksOrRegionsForDurability - 1) regions have ackQuorumSize
                // We are only modifying candidateRackOrRegion if we accept this bookie, so lets only
                // find sets that contain this candidateRackOrRegion
                Integer currentAllocation = allocationToRacksOrRegions.get(candidateRackOrRegion);
                if (currentAllocation == null) {
                    LOG.info("Detected a region that was not initialized {}", candidateRackOrRegion);
                    if (null != alertStatsLogger) {
                        if (candidateRackOrRegion.equals(NetworkTopology.DEFAULT_REGION)) {
                            alertStatsLogger.raise("Failed to resolve network location {}", candidate);
                        } else if (!racksOrRegions.contains(candidateRackOrRegion)) {
                            alertStatsLogger.raise("Unknown region detected {}", candidateRackOrRegion);
                        }
                    }
                    allocationToRacksOrRegions.put(candidateRackOrRegion, 0);
                    currentAllocation = 0;
                }

                int inclusiveLimit = (ackQuorumSize - 1) - (currentAllocation + 1);
                return checkSumOfSubsetWithinLimit(includedRacksOrRegions,
                        remainingRacksOrRegions, minRacksOrRegionsForDurability - 2, inclusiveLimit);
            }

            @Override
            public void addBookie(BookieNode candidate) {
                String candidateRackOrRegion = candidate.getNetworkLocation(distanceFromLeaves);
                candidateRackOrRegion = candidateRackOrRegion.startsWith(NodeBase.PATH_SEPARATOR_STR) ? candidateRackOrRegion.substring(1) : candidateRackOrRegion;
                int oldCount = 0;
                if (null != allocationToRacksOrRegions.get(candidateRackOrRegion)) {
                    oldCount = allocationToRacksOrRegions.get(candidateRackOrRegion);
                }
                allocationToRacksOrRegions.put(candidateRackOrRegion, oldCount + 1);
            }
        }



        final int distanceFromLeaves;
        final int ensembleSize;
        final int writeQuorumSize;
        final int ackQuorumSize;
        final int minRacksOrRegionsForDurability;
        final ArrayList<BookieNode> chosenNodes;
        final Set<String> racksOrRegions;
        final AlertStatsLogger alertStatsLogger;
        private final CoverageSet[] quorums;
        final Predicate<BookieNode> parentPredicate;
        final Ensemble<BookieNode> parentEnsemble;

        protected RRTopologyAwareCoverageEnsemble(RRTopologyAwareCoverageEnsemble that) {
            this.distanceFromLeaves = that.distanceFromLeaves;
            this.ensembleSize = that.ensembleSize;
            this.writeQuorumSize = that.writeQuorumSize;
            this.ackQuorumSize = that.ackQuorumSize;
            this.alertStatsLogger = that.alertStatsLogger;
            this.chosenNodes = (ArrayList<BookieNode>)that.chosenNodes.clone();
            this.quorums = new CoverageSet[that.quorums.length];
            for (int i = 0; i < that.quorums.length; i++) {
                if (null != that.quorums[i]) {
                    this.quorums[i] = that.quorums[i].clone();
                } else {
                    this.quorums[i] = null;
                }
            }
            this.parentPredicate = that.parentPredicate;
            this.parentEnsemble = that.parentEnsemble;
            if (null != that.racksOrRegions) {
                this.racksOrRegions = new HashSet<String>(that.racksOrRegions);
            } else {
                this.racksOrRegions = null;
            }
            this.minRacksOrRegionsForDurability = that.minRacksOrRegionsForDurability;
        }

        protected RRTopologyAwareCoverageEnsemble(int ensembleSize,
                                                  int writeQuorumSize,
                                                  int ackQuorumSize,
                                                  int distanceFromLeaves,
                                                  Set<String> racksOrRegions,
                                                  int minRacksOrRegionsForDurability,
                                                  AlertStatsLogger alertStatsLogger) {
            this(ensembleSize, writeQuorumSize, ackQuorumSize, distanceFromLeaves, null, null,
                 racksOrRegions, minRacksOrRegionsForDurability, alertStatsLogger);
        }

        protected RRTopologyAwareCoverageEnsemble(int ensembleSize,
                                                  int writeQuorumSize,
                                                  int ackQuorumSize,
                                                  int distanceFromLeaves,
                                                  Ensemble<BookieNode> parentEnsemble,
                                                  Predicate<BookieNode> parentPredicate,
                                                  AlertStatsLogger alertStatsLogger) {
            this(ensembleSize, writeQuorumSize, ackQuorumSize, distanceFromLeaves, parentEnsemble, parentPredicate,
                 null, 0, alertStatsLogger);
        }

        protected RRTopologyAwareCoverageEnsemble(int ensembleSize,
                                                  int writeQuorumSize,
                                                  int ackQuorumSize,
                                                  int distanceFromLeaves,
                                                  Ensemble<BookieNode> parentEnsemble,
                                                  Predicate<BookieNode> parentPredicate,
                                                  Set<String> racksOrRegions,
                                                  int minRacksOrRegionsForDurability,
                                                  AlertStatsLogger alertStatsLogger) {
            this.ensembleSize = ensembleSize;
            this.writeQuorumSize = writeQuorumSize;
            this.ackQuorumSize = ackQuorumSize;
            this.distanceFromLeaves = distanceFromLeaves;
            this.alertStatsLogger = alertStatsLogger;
            this.chosenNodes = new ArrayList<BookieNode>(ensembleSize);
            if (minRacksOrRegionsForDurability > 0) {
                this.quorums = new RackOrRegionDurabilityCoverageSet[ensembleSize];
            } else {
                this.quorums = new RackQuorumCoverageSet[ensembleSize];
            }
            this.parentEnsemble = parentEnsemble;
            this.parentPredicate = parentPredicate;
            this.racksOrRegions = racksOrRegions;
            this.minRacksOrRegionsForDurability = minRacksOrRegionsForDurability;
        }

        @Override
        public boolean apply(BookieNode candidate, Ensemble<BookieNode> ensemble) {
            if (ensemble != this) {
                return false;
            }

            // An ensemble cannot contain the same node twice
            if (chosenNodes.contains(candidate)) {
                return false;
            }

            // candidate position
            if ((ensembleSize == writeQuorumSize) && (minRacksOrRegionsForDurability > 0)) {
                if (null == quorums[0]) {
                    quorums[0] = new RackOrRegionDurabilityCoverageSet();
                }
                if (!quorums[0].apply(candidate)) {
                    return false;
                }
            } else {
                int candidatePos = chosenNodes.size();
                int startPos = candidatePos - writeQuorumSize + 1;
                for (int i = startPos; i <= candidatePos; i++) {
                    int idx = (i + ensembleSize) % ensembleSize;
                    if (null == quorums[idx]) {
                        if (minRacksOrRegionsForDurability > 0) {
                            quorums[idx] = new RackOrRegionDurabilityCoverageSet();
                        } else {
                            quorums[idx] = new RackQuorumCoverageSet();
                        }
                    }
                    if (!quorums[idx].apply(candidate)) {
                        return false;
                    }
                }
            }

            return ((null == parentPredicate) || parentPredicate.apply(candidate, parentEnsemble));
        }

        @Override
        public boolean addNode(BookieNode node) {
            // An ensemble cannot contain the same node twice
            if (chosenNodes.contains(node)) {
                return false;
            }

            if ((ensembleSize == writeQuorumSize) && (minRacksOrRegionsForDurability > 0)) {
                if (null == quorums[0]) {
                    quorums[0] = new RackOrRegionDurabilityCoverageSet();
                }
                quorums[0].addBookie(node);
            } else {
                int candidatePos = chosenNodes.size();
                int startPos = candidatePos - writeQuorumSize + 1;
                for (int i = startPos; i <= candidatePos; i++) {
                    int idx = (i + ensembleSize) % ensembleSize;
                    if (null == quorums[idx]) {
                        if (minRacksOrRegionsForDurability > 0) {
                            quorums[idx] = new RackOrRegionDurabilityCoverageSet();
                        } else {
                            quorums[idx] = new RackQuorumCoverageSet();
                        }
                    }
                    quorums[idx].addBookie(node);
                }
            }
            chosenNodes.add(node);

            return ((null == parentEnsemble) || parentEnsemble.addNode(node));
        }

        @Override
        public ArrayList<BookieSocketAddress> toList() {
            ArrayList<BookieSocketAddress> addresses = new ArrayList<BookieSocketAddress>(ensembleSize);
            for (BookieNode bn : chosenNodes) {
                addresses.add(bn.getAddr());
            }
            return addresses;
        }

        /**
         * Validates if an ensemble is valid
         *
         * @return true if the ensemble is valid; false otherwise
         */
        @Override
        public boolean validate() {
            HashSet<BookieSocketAddress> addresses = new HashSet<BookieSocketAddress>(ensembleSize);
            HashSet<String> racksOrRegions = new HashSet<String>();
            for (BookieNode bn : chosenNodes) {
                if (addresses.contains(bn.getAddr())) {
                    return false;
                }
                addresses.add(bn.getAddr());
                racksOrRegions.add(bn.getNetworkLocation(distanceFromLeaves));
            }

            return ((minRacksOrRegionsForDurability == 0) ||
                    (racksOrRegions.size() >= minRacksOrRegionsForDurability));
        }

        @Override
        public String toString() {
            return chosenNodes.toString();
        }
    }

    @Override
    public List<Integer> reorderReadSequence(ArrayList<BookieSocketAddress> ensemble, List<Integer> writeSet, Map<BookieSocketAddress, Long> bookieFailureHistory) {
        return writeSet;
    }

    @Override
    public List<Integer> reorderReadLACSequence(ArrayList<BookieSocketAddress> ensemble, List<Integer> writeSet, Map<BookieSocketAddress, Long> bookieFailureHistory) {
        List<Integer> retList = new ArrayList<Integer>(reorderReadSequence(ensemble, writeSet, bookieFailureHistory));
        if (retList.size() < ensemble.size()) {
            for (int i = 0; i < ensemble.size(); i++) {
                if (!retList.contains(i)) {
                    retList.add(i);
                }
            }
        }
        return retList;
    }
}
