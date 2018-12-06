package com.ggp.players.deepstack;

import com.ggp.IInformationSet;
import com.ggp.utils.strategy.Strategy;

public interface IRegretMatching {
    interface Factory {
        IRegretMatching create();
        String getConfigString();
    }

    void addActionRegret(IInformationSet is, int actionIdx, double regretDiff);
    boolean hasInfoSet(IInformationSet is);
    void getRegretMatchedStrategy(IInformationSet is, Strategy strat);
    void getRegretMatchedStrategy(Strategy strat);
    void initInfoSet(IInformationSet is);
    double getTotalRegret();
}
