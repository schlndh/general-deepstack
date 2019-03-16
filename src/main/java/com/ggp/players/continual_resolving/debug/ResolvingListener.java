package com.ggp.players.continual_resolving.debug;

import com.ggp.IAction;
import com.ggp.ICompleteInformationState;
import com.ggp.IGameListener;
import com.ggp.IPlayer;
import com.ggp.players.continual_resolving.IResolvingInfo;
import com.ggp.players.continual_resolving.IResolvingListener;
import com.ggp.utils.time.StopWatch;

public class ResolvingListener implements IResolvingListener, IGameListener {
    private int totalStateCounter = 0;
    private int actionStateCounter = 0;
    private StopWatch totalReasoningTime = new StopWatch();
    private StopWatch actionReasoningTime = new StopWatch();

    @Override
    public void initEnd(IResolvingInfo resInfo) {
    }

    @Override
    public void resolvingStart(IResolvingInfo resInfo) {
        actionStateCounter = 0;
        actionReasoningTime.reset();
        totalReasoningTime.start();
    }

    @Override
    public void resolvingEnd(IResolvingInfo resInfo) {
        totalReasoningTime.stop();
        actionReasoningTime.stop();
        System.out.println(String.format("Reasoning time: %d ms, states visited: %d",
                actionReasoningTime.getDurationMs(), actionStateCounter));
    }

    @Override
    public void stateVisited(ICompleteInformationState s, IResolvingInfo resInfo) {
        totalStateCounter++;
        actionStateCounter++;
    }

    @Override
    public void resolvingIterationEnd(IResolvingInfo resInfo) {

    }

    @Override
    public void gameStart(IPlayer player1, IPlayer player2) {
    }

    @Override
    public void gameEnd(int payoff1, int payoff2) {
        System.out.println(String.format("TOTAL: Reasoning time: %d ms, states visited: %d",
                totalReasoningTime.getDurationMs(), totalStateCounter));
    }

    @Override
    public void stateReached(ICompleteInformationState s) {
    }

    @Override
    public void actionSelected(ICompleteInformationState s, IAction a) {
    }
}