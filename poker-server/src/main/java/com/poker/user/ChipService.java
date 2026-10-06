package com.poker.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * All chip movements between an account and a table go through here, each in
 * one transaction.
 */
@Service
public class ChipService {
	private final UserRepository users;

	public ChipService(UserRepository users) {
		this.users = users;
	}
	/** Buy-in: account -> table. */
	@Transactional
	public void debit(String username, int amount) {
		if (amount <= 0)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid amount");
		User u = lock(username);
		if (u.getChips() < amount)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough chips");
		u.removeChips(amount);
	}

	/** Cash-out or refund: table -> account. */
	@Transactional
	public void credit(String username, int amount) {
		if (amount < 0)
			throw new IllegalArgumentException("Negative credit");
		lock(username).addChips(amount);
	}

	@Transactional
	public void recordHand(String username, int net) {
		lock(username).recordHand(net);
	}

	private User lock(String username) {
		return users.findForUpdate(username)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
	}
}
