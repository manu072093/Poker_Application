package com.poker.user;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false, length = 20)
	private String username;

	@Column(nullable = false)
	private String passwordHash;

	/**
	 * Chips in the player's account (not counting chips currently sitting at a
	 * table).
	 */
	private long chips;
	private long totalWinnings;
	private int handsPlayed;
	private int handsWon;

	protected User() {
	}

	public User(String username, String passwordHash, long chips) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.chips = chips;
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public long getChips() {
		return chips;
	}

	public long getTotalWinnings() {
		return totalWinnings;
	}

	public int getHandsPlayed() {
		return handsPlayed;
	}

	public int getHandsWon() {
		return handsWon;
	}

	void addChips(long amount) {
		chips += amount;
	}

	void removeChips(long amount) {
		chips -= amount;
	}

	void recordHand(int net) {
		handsPlayed++;
		totalWinnings += net;
		if (net > 0)
			handsWon++;
	}
}
