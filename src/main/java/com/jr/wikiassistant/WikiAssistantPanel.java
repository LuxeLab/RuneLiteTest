package com.jr.wikiassistant;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class WikiAssistantPanel extends PluginPanel
{
	private final WikiAssistantService service;

	private final JTextField questionField = new JTextField();
	private final JTextArea outputArea = new JTextArea();
	private final JButton askButton = new JButton("Ask Wiki");
	private final JButton clearButton = new JButton("Clear");
	private final JLabel statusLabel = new JLabel("Ready");

	@Inject
	public WikiAssistantPanel(WikiAssistantService service)
	{
		this.service = service;

		setLayout(new BorderLayout(0, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("OSRS Wiki Assistant");
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

		questionField.setPreferredSize(new Dimension(0, 32));
		questionField.setToolTipText("Ask a question grounded in OSRS Wiki + your live RuneLite context");
		questionField.setMargin(new Insets(4, 6, 4, 6));

		outputArea.setEditable(false);
		outputArea.setLineWrap(true);
		outputArea.setWrapStyleWord(true);
		outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		outputArea.setMargin(new Insets(8, 8, 8, 8));
		outputArea.setText(
			"Try:\n"
				+ "• What cooking level can I reach with the raw food in my bank?\n"
				+ "• Best fish to cook for XP at my level\n"
				+ "• How do I start Recipe for Disaster?"
		);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		askButton.addActionListener(e -> ask());
		questionField.addActionListener(e -> ask());

		clearButton.addActionListener(e ->
		{
			questionField.setText("");
			outputArea.setText("Ready for your next question.");
			setStatus("Ready");
		});

		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.setOpaque(false);
		top.add(title, BorderLayout.NORTH);
		top.add(questionField, BorderLayout.CENTER);

		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttonRow.setOpaque(false);
		buttonRow.add(askButton);
		buttonRow.add(clearButton);
		buttonRow.add(statusLabel);
		top.add(buttonRow, BorderLayout.SOUTH);

		JScrollPane outputScroll = new JScrollPane(outputArea);
		outputScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR));

		add(top, BorderLayout.NORTH);
		add(outputScroll, BorderLayout.CENTER);
	}

	private void ask()
	{
		System.out.println("[WikiAssistantPanel] ask() invoked");
		String q = questionField.getText();
		if (q == null || q.isBlank())
		{
			System.out.println("[WikiAssistantPanel] empty question");
			setStatus("Enter a question");
			return;
		}

		System.out.println("[WikiAssistantPanel] question=" + q);
		askButton.setEnabled(false);
		setStatus("Thinking...");
		outputArea.setText("Working on it...\n");

		new Thread(() ->
		{
			System.out.println("[WikiAssistantPanel] worker thread start");
			String answer;
			try
			{
				answer = service.answer(q);
			}
			catch (Throwable ex)
			{
				ex.printStackTrace();
				answer = "Error while answering: " + ex.getClass().getSimpleName() + " - " + ex.getMessage();
			}

			System.out.println("[WikiAssistantPanel] worker finished, answer length=" + (answer == null ? -1 : answer.length()));
			final String finalAnswer = answer;
			SwingUtilities.invokeLater(() ->
			{
				outputArea.setText(finalAnswer);
				askButton.setEnabled(true);
				setStatus("Done");
			});
		}, "wiki-assistant-worker").start();
	}

	private void setStatus(String status)
	{
		statusLabel.setText(status == null ? "" : status);
	}
}
