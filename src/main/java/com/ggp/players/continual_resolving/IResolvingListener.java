package com.ggp.players.continual_resolving;

import com.ggp.ICompleteInformationState;

public interface IResolvingListener {
    void initEnd(IResolvingInfo resInfo);
    void resolvingStart(IResolvingInfo resInfo);
    void resolvingEnd(IResolvingInfo resInfo);
    void stateVisited(ICompleteInformationState s, IResolvingInfo resInfo);
    void resolvingIterationEnd(IResolvingInfo resInfo);
}
