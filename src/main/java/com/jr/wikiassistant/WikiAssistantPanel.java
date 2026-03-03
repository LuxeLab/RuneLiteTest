package com.jr.wikiassistant;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class WikiAssistantPanel extends PluginPanel
{
	private final WikiAssistantService service;

	private final JTextField questionField = new JTextField();
	private final JTextArea outputArea = new JTextArea();
	private final JButton askButton = new JButton("Ask");

	@Inject
	public WikiAssistantPanel(WikiAssistantService service)
	{
		this.service = service;

		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		questionField.setPreferredSize(new Dimension(0, 32));
		questionField.setToolTipText("Ask an OSRS question grounded in wiki data");

		outputArea.setEditable(false);
		outputArea.setLineWrap(true);
		outputArea.setWrapStyleWord(true);
		outputArea.setText("Ask a question, e.g.:\nWhat cooking level can I reach with the raw food in my bank?");

		askButton.addActionListener(e -> ask());
		questionField.addActionListener(e -> ask());

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.add(questionField, BorderLayout.CENTER);
		top.add(askButton, BorderLayout.EAST);

		add(top, BorderLayout.NORTH);
		add(new JScrollPane(outputArea), BorderLayout.CENTER);
	}

	private void ask()
	{
		String q = questionField.getText();
		if (q == null || q.isBlank())
		{
			return;
		}

		askButton.setEnabled(false);
		outputArea.setText("Thinking...\n");

		new Thread(() ->
		{
			String answer;
			try
			{
				answer = service.answer(q);
			}
			catch (Exception ex)
			{
				answer = "Error while answering: " + ex.getMessage();
			}

			final String finalAnswer = answer;
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				outputArea.setText(finalAnswer);
				askButton.setEnabled(true);
			});
		}, "wiki-assistant-worker").start();
	}
}
