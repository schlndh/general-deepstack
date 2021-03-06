package com.ggp.games.LeducPoker;

import com.ggp.*;
import com.ggp.games.LeducPoker.actions.CallAction;
import com.ggp.games.LeducPoker.actions.DealCardAction;
import com.ggp.games.LeducPoker.actions.FoldAction;
import com.ggp.games.LeducPoker.actions.RaiseAction;
import com.ggp.games.LeducPoker.percepts.*;
import com.ggp.utils.UniformRandomNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CompleteInformationState implements ICompleteInformationState {
    private static final long serialVersionUID = 1L;
    private final InformationSet player1IS;
    private final InformationSet player2IS;
    private final int actingPlayer;
    private transient ArrayList<IAction> legalDealActions;

    private void initLegalDealActions() {
        Rounds round = player2IS.getRound();
        if (round == Rounds.PrivateCard || round == Rounds.PublicCard) {
            int cardsPerSuite = player1IS.getGameDesc().getCardsPerSuite();
            legalDealActions = new ArrayList<>(cardsPerSuite);

            int player;
            if (round == Rounds.PublicCard) {
                player = 0;
            } else {
                player = player1IS.getPrivateCard() == null ? 1 : 2;
            }
            for (int i = 0; i < cardsPerSuite; ++i ){
                legalDealActions.add(new DealCardAction(i, player));
            }
            if (round == Rounds.PublicCard && player1IS.getPrivateCard() == player2IS.getPrivateCard()) {
                legalDealActions.remove((int) player1IS.getPrivateCard());
            }
        }
    }

    public CompleteInformationState(InformationSet player1IS, InformationSet player2IS, int actingPlayer) {
        this.player1IS = player1IS;
        this.player2IS = player2IS;
        this.actingPlayer = actingPlayer;
        initLegalDealActions();
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initLegalDealActions();
    }

    @Override
    public boolean isTerminal() {
        return player1IS.isTerminal();
    }

    @Override
    public int getActingPlayerId() {
        return actingPlayer;
    }

    @Override
    public double getPayoff(int player) {
        if (!isTerminal() || (player != 1 && player != 2)) return 0;
        int winner = 0;
        if (player1IS.getFoldedByPlayer() != 0) {
            winner = player1IS.getFoldedByPlayer() == 1 ? 2 : 1;
        } else {
            int publicCard = player1IS.getPublicCard();
            int c1 = player1IS.getPrivateCard(), c2 = player2IS.getPrivateCard();
            if (c1 == publicCard) {
                winner = 1;
            } else if (c2 == publicCard) {
                winner = 2;
            } else if (c1 > c2) {
                winner = 1;
            } else if (c2 > c1) {
                winner = 2;
            }
        }

        InformationSet playerIS = (player == 1) ? player1IS : player2IS;
        if (winner == 0) {
            return 0; // draw, both players get their bets back
        } else if (winner == player) {
            return playerIS.getPotSize() - playerIS.getMyBets();
        } else {
            return -playerIS.getMyBets();
        }
    }

    @Override
    public List<IAction> getLegalActions() {
        if (isTerminal()) return null;
        if (actingPlayer == 1) {
            return player1IS.getLegalActions();
        } else if (actingPlayer == 2) {
            return player2IS.getLegalActions();
        } else {
            // random node
            return legalDealActions;
        }
    }

    @Override
    public IInformationSet getInfoSetForPlayer(int player) {
        if (player == 1) return player1IS;
        if (player == 2) return player2IS;
        return null;
    }

    @Override
    public ICompleteInformationState next(IAction a) {
        IInformationSet is1 = player1IS, is2 = player2IS;
        if (actingPlayer == 1) {
            is1 = is1.next(a);
        } else if (actingPlayer == 2) {
            is2 = is2.next(a);
        }
        for (IPercept p: getPercepts(a)) {
            if (p.getTargetPlayer() == 1) {
                is1 = is1.applyPercept(p);
            } else if (p.getTargetPlayer() == 2) {
                is2 = is2.applyPercept(p);
            }
        }
        Rounds r1 = ((InformationSet)is1).getRound(), r2 = ((InformationSet)is2).getRound();
        Rounds nextRound = r1.ordinal() < r2.ordinal() ? r1 : r2;
        int newActingPlayer = 0;

        switch (nextRound) {
            case PrivateCard:
            case PublicCard:
            case Start:
            case End:
                newActingPlayer = 0;
                break;
            case Bet1:
            case Bet2:
                newActingPlayer = actingPlayer == 1 ? 2 : 1;
                break;
        }

        return new CompleteInformationState((InformationSet) is1, (InformationSet) is2, newActingPlayer);
    }

    @Override
    public Iterable<IPercept> getPercepts(IAction a) {
        ArrayList<IPercept> ret = new ArrayList<>();
        InformationSet otherPlayer = (actingPlayer == 1) ? player2IS : player1IS;
        if (a.getClass() == FoldAction.class) {
            ret.add(new OpponentFoldedPercept(otherPlayer.getOwner()));
            return ret;
        } else if (a.getClass() == RaiseAction.class) {
            int newPot = otherPlayer.getPotSize();
            if (otherPlayer.getRaisesUsedThisRound() > 0) newPot += otherPlayer.getRaiseAmount();

            int diff = otherPlayer.getRaiseAmount() - otherPlayer.getRemainingMoney();
            int increase = otherPlayer.getRaiseAmount();
            if (diff > 0) {
                ret.add(new ReturnedMoneyPercept(actingPlayer, diff));
                increase = otherPlayer.getRemainingMoney();
            }
            ret.add(new PotUpdatePercept(otherPlayer.getOwner(), newPot + increase));
            return ret;
        } else if (a.getClass() == CallAction.class) {
            InformationSet player = (InformationSet) getInfoSetForActingPlayer();
            boolean wasRaised = player.getRaisesUsedThisRound() > 0;
            // end betting round only if this is not the first action of the betting round
            if (actingPlayer == 2 || wasRaised) {
                if (wasRaised) {
                    ret.add(new PotUpdatePercept(otherPlayer.getOwner(), otherPlayer.getPotSize() + Math.min(player.getRaiseAmount(), player.getRemainingMoney())));
                }
                ret.add(new BettingRoundEndedPercept(1));
                ret.add(new BettingRoundEndedPercept(2));
            }
            return ret;
        } else if (a.getClass() == DealCardAction.class) {
            DealCardAction dc = (DealCardAction) a;
            if (dc.isPublic()) {
                ret.add(new CardRevealedPercept(1, dc.getCard(), true));
                ret.add(new CardRevealedPercept(2, dc.getCard(), true));
            } else {
                ret.add(new CardRevealedPercept(dc.getPlayer(), dc.getCard(), false));
            }
            return ret;
        }
        return null;
    }

    @Override
    public boolean isLegal(IAction a) {
        if (a == null || isTerminal()) return false;
        if (actingPlayer == 1) return player1IS.isLegal(a);
        if (actingPlayer == 2) return player2IS.isLegal(a);
        return legalDealActions.contains(a);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompleteInformationState that = (CompleteInformationState) o;
        return actingPlayer == that.actingPlayer &&
                Objects.equals(player1IS, that.player1IS) &&
                Objects.equals(player2IS, that.player2IS);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player1IS, player2IS, actingPlayer);
    }

    @Override
    public IRandomNode getRandomNode() {
        if (!isRandomNode()) return null;
        return new UniformRandomNode(getLegalActions());
    }

    @Override
    public String toString() {
        InformationSet is1 = player1IS, is2 = player2IS;
        return String.format("[%s] %d: 1(%s, %d/%d), 2(%s, %d/%d), Public(%s), Pot = %d, Bets = %d/%d",
                is2.getRound(), actingPlayer, is1.getPrivateCard(), is1.getRemainingMoney(),
                is1.getStartingMoney(), is2.getPrivateCard(), is2.getRemainingMoney(), is2.getStartingMoney(),
                is1.getPublicCard(), is1.getPotSize(), is1.getRaisesUsedThisRound(), is1.getGameDesc().getBetsPerRound());
    }
}
