package com.ggp.players.continual_resolving.utils;

import com.ggp.players.continual_resolving.trackers.CFRDTracker;
import com.ggp.players.continual_resolving.trackers.IGameTraversalTracker;
import com.ggp.utils.IUtilityEstimator;

public class ContinualResolvingUtilityEstimatorWrapper implements IUtilityEstimator {
    public static class Factory implements  IUtilityEstimator.IFactory {
        private IUtilityEstimator.IFactory ueFactory;

        public Factory(IFactory ueFactory) {
            if (ueFactory == null) {
                throw new IllegalArgumentException("Inner utility estimator factory can't be null!");
            }
            this.ueFactory = ueFactory;
        }

        @Override
        public IUtilityEstimator create() {
            return null;
        }

        @Override
        public String getConfigString() {
            return "CRUEW{" + ueFactory.getConfigString() + "}";
        }
    }

    private IUtilityEstimator utilityEstimator;

    public ContinualResolvingUtilityEstimatorWrapper(IUtilityEstimator utilityEstimator) {
        this.utilityEstimator = utilityEstimator;
    }

    @Override
    public UtilityEstimate estimate(IGameTraversalTracker tracker) {
        return utilityEstimator.estimate(tracker);
    }

    @Override
    public boolean canEstimate(IGameTraversalTracker tracker) {
        return ((CFRDTracker)tracker).wasMyNextTurnReached() && utilityEstimator.canEstimate(tracker);
    }

    @Override
    public IUtilityEstimator copy() {
        return new ContinualResolvingUtilityEstimatorWrapper(utilityEstimator.copy());
    }
}
