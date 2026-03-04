package com.jr.pvphelpr;

public enum PvpHelprSpell
{
	NONE(""),
	FIRE_SURGE("Fire Surge"),
	ICE_BARRAGE("Ice Barrage"),
	BLOOD_BARRAGE("Blood Barrage"),
	ENTANGLE("Entangle"),
	TELE_BLOCK("Tele Block");

	private final String label;

	PvpHelprSpell(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
