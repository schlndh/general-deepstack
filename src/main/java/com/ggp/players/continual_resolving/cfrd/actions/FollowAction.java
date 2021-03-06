package com.ggp.players.continual_resolving.cfrd.actions;

import com.ggp.IAction;

public class FollowAction implements IAction {
    private static final long serialVersionUID = 1L;
    public static FollowAction instance = new FollowAction();
    private FollowAction() {}

    @Override
    public int hashCode() {
        return this.getClass().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return o != null && this.getClass().equals(o.getClass());
    }

    @Override
    public String toString() {
        return "FollowAction{}";
    }
}
