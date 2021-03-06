package com.ggp.utils;

import com.ggp.*;

import java.util.List;
import java.util.Objects;

/**
 * Base class for CIS wrappers, delegates most methods to the original state.
 */
public abstract class CompleteInformationStateWrapper implements ICompleteInformationState {
    private static final long serialVersionUID = 1L;
    protected ICompleteInformationState state;

    public CompleteInformationStateWrapper(ICompleteInformationState state) {
        this.state = state;
    }

    @Override
    public boolean isTerminal() {
        return state.isTerminal();
    }

    @Override
    public int getActingPlayerId() {
        return state.getActingPlayerId();
    }

    @Override
    public double getPayoff(int player) {
        return state.getPayoff(player);
    }

    @Override
    public List<IAction> getLegalActions() {
        return state.getLegalActions();
    }

    @Override
    public IInformationSet getInfoSetForPlayer(int player) {
        return state.getInfoSetForPlayer(player);
    }

    @Override
    public abstract ICompleteInformationState next(IAction a);

    @Override
    public Iterable<IPercept> getPercepts(IAction a) {
        return state.getPercepts(a);
    }

    @Override
    public boolean isLegal(IAction a) {
        return state.isLegal(a);
    }

    @Override
    public IRandomNode getRandomNode() {
        return state.getRandomNode();
    }

    public ICompleteInformationState getOrigState() {
        return state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompleteInformationStateWrapper that = (CompleteInformationStateWrapper) o;
        return Objects.equals(state, that.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state);
    }
}
